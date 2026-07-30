# Plan: Fake-stream solid archives (RAR/CBR/7z)

## Goal

Replace **full download → local open** for network solid archives with **fake stream mode**:

1. Open reader **immediately** (no wait for entire `.rar` / `.7z` on disk).
2. **Sequential** network read (SMB keep-open / WebDAV body stream).
3. **Stream-extract** members into a durable **extract cache** (images, not the archive blob).
4. Couple extract progress to **reader prefetch** (only a few pages need to exist for display).
5. **Lazy member list** as headers/data are discovered.
6. Persist **index + thumbs** so reopen is cheap; plan **resume / cold open** when there is no central directory.

This is orthogonal to true ZIP/TAR range-stream (already shipped). Solid formats cannot random-access decompress; “stream” here means **progressive sequential extract**, not EOCD-class seeks.

---

## Current baseline (what we change)

| Path | Behavior today |
|------|----------------|
| ZIP/CBZ/TAR/CBT network | True stream: range I/O + `ArchiveStreamPageCache` |
| RAR/CBR/7z network | `RemoteArchiveOpen.ensure*Archive` full file → `useArchivePageLoader` |
| Covers for solid | Skipped (`isSolidArchiveFileName` / `writeCoverFromOpenArchive`) |
| Stream open native | Explicitly ZIP+TAR only (`archive.c` `use_stream_io` branch) |

Touchpoints: `SmbBrowserScreen` / `WebDavBrowserScreen` `openArchive`, `RemoteArchiveOpen`, `SmbCache`/`WebDavCache`, `ArchivePageLoader`, `PageLoader` (fixed `size`), `ArchiveCoverCache`, `MediaTypes.SOLID_*`.

---

## Format reality (design constraints)

| | RAR / CBR (RAR5) | 7z |
|--|------------------|-----|
| Header layout | Mostly **sequential** from start | Start header → often **encoded header at EOF** |
| Solid data | Decompress **0…N** to get page N | Same |
| Pure `InputStream` open | **Good fit** for fake stream | **Fragile** — listing often needs seek/tail |
| Member list without full file | Progressive as we walk | Often need full archive or seekable copy first |
| Random page without prior extract | No (solid) | No (solid) |

**Recommendation:** implement one **SolidExtract** pipeline; treat RAR/CBR as primary; **7z** uses same UX with a **hybrid backend** when pure sequential open fails (see S1).

---

## Architecture

```
Browse open .cbr/.rar/.7z
  → ReaderScreenArgs.SmbSolidExtract / WebDavSolidExtract  (new; or reuse stream args + kind)
  → useSolidExtractPageLoader
       │
       ├─ SequentialRemoteSource          // forward-only bytes (SMB File / WebDAV stream)
       ├─ SolidExtractSession             // single-flight sequential extract loop
       ├─ SolidExtractCache               // disk: index + page files
       └─ PageLoader adapter              // serves PathSource from extract cache;
                                          // waits / drives extract for missing pages
```

### 1. Sequential network source

Not `ArchiveByteSource.readAt` (random). Forward cursor:

```kotlin
interface SequentialByteSource : AutoCloseable {
    val size: Long?                    // if known (SMB EOF / WebDAV Content-Length)
    fun read(buf: ByteArray, off: Int, len: Int): Int  // next bytes; 0 = EOF
    // optional: fun skip(n: Long) for libarchive skip callback (still forward-only)
}
```

| Transport | Implementation |
|-----------|----------------|
| **SMB** | `SmbGateway.withOpenFile` + sequential `file.read(buf, offset, …)` advancing offset; keep-open for whole reader session (same idea as stream keep-open) |
| **WebDAV** | Single GET (or ranged 0–EOF) `InputStream`; avoid reopen per page |

Lib that accepts it: **libarchive** sequential open (`archive_read_open` + read/skip callbacks, **no seek** or seek-only-forward). Already linked with `format_rar5` + `format_7zip` on the **local** path; solid fake-stream is a **new sequential mode**, not the ZIP CD stream path.

Do **not** route solid through current `openArchiveStream` ZIP/TAR indexers.

### 2. Solid extract cache (disk)

Prefer a dedicated tree (clear semantics, LRU separate from full-file archive downloads):

```
{dataDir}/cache/solid_extract/{sha256(cacheKey)}/
  index.json          # sidecar — see § Future cache
  pages/000000.jpg    # zero-padded index + real ext
  pages/000001.png
  …
```

- **Key**: same identity as stream covers (`smb:{sourceId}:{remotePath}`, `webdav:…`).
- **Budget**: share or mirror Advanced read-cache / `ArchiveStreamPageCache` policy; protect from wiping while reader open.
- **Do not** require storing the full `.rar`/`.7z` blob for the happy path (disk = extracted images only). Optional fallback may still write the archive under existing `smb_cache` / `webdav_cache` (see 7z hybrid).

Reuse of “current archive cache folder” can mean: **same cache budget / roots family** as SMB page cache, with a `solid_extract/` sibling of `smb_cache/` — not dumping pages next to a half-downloaded archive file.

### 3. Extract session + reader prefetch coupling

Single background worker per open archive (solid decompress is not parallelizable usefully):

| Concept | Behavior |
|---------|----------|
| `extractCursor` | Next member index not yet fully written to disk |
| `knownCount` | Members discovered so far (headers seen) |
| `complete` | Hit archive EOF; list + extract finished |
| `request(i)` | If `pages/i` cached → ready; else signal **target ≥ i** |
| Prefetch | Target = `max(userPage + preloadImage, …)` (same window spirit as `StreamArchivePageLoader`) |
| Seek backward | Serve from disk only — free |
| Seek forward past cursor | Wait while sequential extract catches up (show page loading) |
| Cancel distant work | Cannot “cancel decompress of middle” without aborting session; only stop **after** current member if user left reader |

Loop (simplified):

```
open sequential source + libarchive
while next_header OK:
  if playable image:
    append to in-memory list / index
    if index <= target OR shouldPrefetch(index):
      extract member → pages/{index}.ext  (atomic tmp+rename)
      if index == 0: write thumb cover
    else:
      // optional: skip body only if format allows without solid dependency
      // solid: must still consume/decompress to advance
  update knownCount; notify waiters
mark complete; flush index.json
```

**Solid truth:** “skip write” still pays network + decompress; only saves disk. Still valuable if user quits early (stop session → less total work).

### 4. Lazy file list / page count

`PageLoader` today takes a fixed `size` and builds `pages = (0 until size)`.

**MVP options (pick one in implementation):**

| Approach | Pros | Cons |
|----------|------|------|
| **A. Growable size** | True lazy UX | Reader UI (pager, slider, progress) must observe `size` Flow |
| **B. Probe-then-open** | Fixed size API | Solid RAR probe ≈ walk whole stream; weak win |
| **C. Open after page 0 + optimistic large size** | Small API churn | Hacky empty tail pages |

**Recommended: A (growable)** for this feature:

- Start reader when **page 0** is ready (`knownCount ≥ 1`).
- `size` grows with the **lazy member list** (headers discovered so far); freezes at EOF.
- **Seek bar is safe:** max index = `listedCount - 1` only — never past unknown pages.
- For solid, list and extract stay aligned on the processed prefix (header then body write). List does not race far ahead of extract; discovering further members requires advancing the sequential cursor (still pays decompress).
- History progress: write only for indices that exist; clamp on reopen.

### 5. Thumb / cover when solid is opened

Today solid covers are skipped. With fake stream:

1. When page 0 lands in extract cache → run existing subsample JPEG path (`ArchiveCoverCache` / 768 edge).
2. Key by remote `cacheKey` (not local path).
3. Browse grid: `isCached` hit on next list paint for archives user has opened.
4. Do **not** depend on full extract; cover as soon as first image exists.

Optional later (browse without open): budgeted cover-only sequential try (old C1) — **out of scope** for first solid-extract PR unless cheap reuse of same session code with early abort after page 0.

---

## Future: cached RAR/7z access without central directory

Solid formats have no ZIP-like CD for free listing. Persist what we learn.

### `index.json` (sidecar)

```json
{
  "v": 1,
  "cacheKey": "smb:1:Comics/foo.cbr",
  "remoteSize": 123456789,
  "remoteMtimeHint": 0,
  "format": "rar5|7z|unknown",
  "complete": true,
  "members": [
    { "i": 0, "name": "001.jpg", "ext": "jpg", "uncSize": 12345 },
    { "i": 1, "name": "002.jpg", "ext": "jpg", "uncSize": 12000 }
  ]
}
```

| Field | Why |
|-------|-----|
| `members[]` | Page count + extensions without re-open |
| `complete` | Safe to treat as folder gallery |
| `remoteSize` | Invalidate if remote size changes |
| Optional later | per-member compressed offset (only useful for **non-solid** RAR resume) |

### Cold open matrix

| Disk state | Action |
|------------|--------|
| `complete` + all `pages/i` present | **Folder-like reader** from extract cache — **no network** |
| Partial pages + index | Reopen sequential from **byte 0**; **skip-write** until first missing; continue to target + prefetch |
| Index only, no pages | Sequential extract from 0 (list known for UI size if complete list was saved mid-way — rare) |
| Nothing | Fresh fake-stream session |
| remoteSize mismatch | Invalidate index + pages (or re-validate) |

**Solid resume limit:** cannot truly resume mid-solid-block; always restart decompress from start. Cache still wins for:

- already-read prefix (instant back-seek),
- complete archives (offline reread),
- cover thumbs,
- knowing page count after one full pass.

### Non-solid RAR (later optimization)

If we detect non-solid, optional path: seekable stream extract like ZIP (libarchive seek) or fall into true stream mode. Not required for MVP; detection can be best-effort.

---

## 7z hybrid (required for honesty)

```
try:
  sequential libarchive open on SequentialByteSource
if open/list fails (needs seek / EOF header):
  fall back:
    progressive or full download archive → smb_cache/webdav_cache file
    then SolidExtractSession over local FileInputStream / fd
    still write pages into solid_extract/  (unified reader)
```

UI still “opens fast” only if sequential works **or** we start download in parallel and extract as soon as local file is complete (download progress snackbar — closer to today). Stretch: extract-while-downloading into a growing local file with seek (complex; defer).

**MVP policy:** RAR/CBR sequential-first; 7z try sequential → on failure use full-file fallback then same extract-cache reader (so thumbs + index still unify).

---

## Integration surface

### Open path

Replace solid branch in `SmbBrowserScreen` / `WebDavBrowserScreen`:

```kotlin
if (isStreamableArchiveFileName(...)) { /* existing true stream */ }
else if (isSolidArchiveFileName(...)) {
  // NEW: navigate SolidExtract reader — no ensure*Archive full DL first
}
else { /* rare other → full DL */ }
```

### Reader args / history

- New `ReaderScreenArgs.SmbSolidExtract` / `WebDavSolidExtract` (or flag on stream args).
- `LocalHistory` tokens parallel to stream archive.
- Sibling navigator: include solid extract archives in prev/next like stream (same folder listing filter expansion).

### Large archive warn

Keep size probe + confirm for **huge** remotes (existing 128 MiB warn). Fake stream reduces “must download all before any page” but reading to the end still moves ~full compressed bytes.

### Native / JNI

New sequential extract API (sketch):

- `openSolidSequential(bridge, size)` — format rar/7z (+ filters), no ZIP CD.
- `solidNextPlayable(): SolidMember?` or callback-driven extract-to-fd.
- Or keep extract loop in Kotlin via a thin “pull next header + extract current to fd” JNI.

Prefer **not** bloating the ZIP stream mutex path; separate session type under `ArchiveAccess` or dedicated lock (long-lived solid extract must not block ZIP covers forever — or serialize with clear priority).

### Prefetch

Mirror `StreamArchivePageLoader`:

- Extract jobs single-flight.
- Interactive page raises `targetIndex`.
- Prefetch window = `Settings.preloadImage`.
- `openSource` = `PathSource` on extract cache file (same as stream pages).

---

## Phases

### S0 — Cache + index primitives (small)

- `SolidExtractCache`: paths, atomic page write, `index.json` read/write, presence checks (StrictMode-safe like other caches).
- Thumb write helper for remote solid keys (lift solid skip when source is extract page 0).

### S1 — Sequential extract engine + RAR/CBR reader (priority)

- `SequentialByteSource` SMB + WebDAV.
- libarchive sequential open for RAR/RAR5.
- `useSolidExtractPageLoader` + growable or interim size strategy.
- Wire browser open + history + cover on page 0.
- Prefetch coupling.
- Cold open: complete extract → no network.

### S2 — 7z + fallback

- Enable 7z in sequential path; hybrid full-file fallback into same extract cache.
- Large-file warn + progress.

### S3 — Resume / invalidation polish

- Partial resume skip-write from 0.
- remoteSize invalidation.
- LRU trim of `solid_extract` (complete archives preferred retention; incomplete younger?).
- Optional: non-solid RAR promote to seek stream later.

### Out of scope (separate tracks)

- EPUB/PDF covers/readers (prior plan).
- Browse-grid solid cover without ever opening (budgeted cover-only).
- True parallel multi-archive solid extract.

---

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Solid seek-to-last-page ≈ full decompress | Expected; show loading; prefetch only near viewport |
| Growable `PageLoader` churn | Design size Flow early; or MVP-0 fixed size after short probe |
| 7z needs EOF | Hybrid fallback; don’t promise pure stream for 7z |
| Holding `ArchiveAccess` for long extract | Separate solid session lock; don’t starve ZIP covers indefinitely |
| Disk larger than compressed archive | Cap extract cache; prefer complete-archive eviction last |
| User quits mid-extract | Cancel session; keep partial pages + incomplete index |
| Passworded solid | Same passwd provider as local archive open |
| smbj InputStream vs read-at | Prefer keep-open sequential `read` at advancing offset (already proven in download/stream) |

---

## Success criteria

1. Open network `.cbr`/`.rar` → first page without full archive file in `smb_cache`.
2. Turning pages advances sequential extract; back pages hit disk.
3. Prefetch keeps ~`preloadImage` pages ahead when network allows.
4. Page 0 → durable thumb for browse/history.
5. Second open of fully read archive → offline from extract cache + index.
6. 7z either sequential or graceful full-file fallback without a third code path for the reader UI.

---

## Recommended decision

- **Yes:** fake-stream solid extract is the right product model for network RAR/CBR (and 7z with hybrid).
- **Ship order:** S0 → S1 (RAR/CBR) → S2 (7z) → S3 (resume polish).
- **Keep** true ZIP/TAR stream as-is; **do not** merge solid into ZIP CD code.
- Full-archive download path remains **fallback** (7z / failure), not the primary RAR path.

No implementation until this plan is approved (especially: growable page count vs MVP-0 spinner, and 7z hybrid policy).
