@file:Suppress("SameParameterValue")

package com.hippo.ehviewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.ehviewer.core.preferences.DataStorePreferences
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import eu.kanade.tachiyomi.ui.reader.setting.OrientationType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import java.util.Locale
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.map

@Stable
@Composable
inline fun <R, T> PrefDelegate<T>.collectAsState(crossinline transform: @DisallowComposableCalls (T) -> R): State<R> {
    val flow = remember { valueFlow().map { transform(it) } }
    val init = value
    return flow.collectAsState(transform(init))
}

@Stable
@Composable
fun <T> PrefDelegate<T>.collectAsState(): State<T> {
    val flow = remember { valueFlow() }
    val init = value
    return flow.collectAsState(init)
}

@Stable
@Composable
fun <T> PrefDelegate<T>.asMutableState(): MutableState<T> {
    val readOnly = collectAsState()
    return remember {
        object : MutableState<T> {
            override var value: T
                get() = readOnly.value
                set(value) {
                    this@asMutableState.value = value
                }
            override fun component1() = readOnly.value
            override fun component2() = this@asMutableState::value::set
        }
    }
}

object Settings : DataStorePreferences(null) {
    @Suppress("ktlint:standard:backing-property-naming")
    private val _favFlow = MutableSharedFlow<Unit>()
    val favChangesFlow = _favFlow.debounce(1000)
    var favCat by stringArrayPref("fav_cat", 10, "Favorites").emitTo(_favFlow)
    var favCount by intArrayPref("fav_count", 10).emitTo(_favFlow)
    var favCloudCount by intPref("fav_cloud", 0).emitTo(_favFlow)

    // Eh
    val gallerySite = intPref("gallery_site_2", 0).observed(::updateWhenGallerySiteChanges)
    val defaultFavSlot = intPref("default_favorite_slot", -2)
    val theme = intPref("theme_2", -1).observed(::updateWhenThemeChanges)
    val blackDarkTheme = boolPref("black_dark_theme", false)
    val harmonizeCategoryColor = boolPref("harmonize_category_color", true)
    val launchPage = intPref("launch_page_2", 0)

    /** 0 = detail (list), 1 = thumb (grid). Default: thumb. */
    val listMode = intPref("list_mode_2", 1)

    /**
     * Folder browser content filter: 0=Galleries (default), 1=Media, 2=Video, 3=Folder.
     * See [com.hippo.ehviewer.library.BrowseContentMode].
     */
    val browseContentMode = intPref("browse_content_mode", 0)

    /**
     * Folder-view UI listing sort field: 0 = Name, 1 = Date.
     * Separate from [librarySortMode]. Does not change DirectoryListing / gallery scan /
     * folder-thumb / open-gallery name order. Default Name.
     */
    val browseSortMode = intPref("browse_sort_mode", 0)

    /**
     * Folder-view UI listing direction for [browseSortMode].
     * Default ascending (A→Z / oldest→newest).
     */
    val browseSortAscending = boolPref("browse_sort_ascending", true)

    /**
     * When true, folder browser directory grid cells show a cover thumb from lazy-scan
     * metadata (direct image, else first image from ≤3 leaf peeks). Off = icon only.
     * Default on; also exposed under Settings → General.
     */
    val browseFolderThumbs = boolPref("browse_folder_thumbs", true)

    /**
     * Persist completed SMB/WebDAV/local-folder lazy-scan listings, one JSON index per source.
     * Off keeps listings in RAM only; on also restores them from disk across app restarts.
     */
    val networkFolderIndexCache = boolPref("network_folder_index_cache", true)

    /**
     * On any RAM or disk listing-cache hit, run the fast current-directory scan that detects
     * added and deleted child folders. Off returns the cached listing without re-list work.
     * Applies to network and local folder browse.
     */
    val networkFolderIndexQuickScan = boolPref("network_folder_index_quick_scan", true)

    /**
     * When true, local folder listing / slim / library scan count archive image pages
     * and persist the archive's own seek index (ZIP CD / EPUB / PDF). Off skips that
     * work; already-cached listing and reader indexes still show. Settings → General.
     */
    val browseArchivePageCount = boolPref("browse_archive_page_count", false)

    /**
     * When true (default): tap video → in-app Media3 player; long-press → external app.
     * When false: tap → external; long-press → Media3.
     * Settings → General.
     */
    val useMedia3Player = boolPref("use_media3_player", true)

    /**
     * Preferred external video player as flattened [android.content.ComponentName]
     * (`package/class`). Empty = system chooser. Settings → General.
     */
    val defaultVideoPlayerComponent = stringPref("default_video_player_component", "")

    /**
     * When true, external HTTP video open exposes **all** videos and subtitle files in the
     * current directory (playlist / next-prev friendly). When false (default), only the
     * opened video plus matching sidecar subs are published. Settings → General.
     */
    val externalVideoAccessDir = boolPref("external_video_access_dir", true)
        .observed(::updateWhenExternalVideoAccessDirChanges)

    /**
     * When true (and [externalVideoAccessDir] is on), external open hands the folder to the
     * player as a loopback **m3u8** playlist of every video in the directory (current file
     * first). When false, the opened video URI is the intent data and multi-file extras are
     * attached best-effort. Settings → General (nested under folder access).
     */
    val externalVideoPassFolderPlaylist = boolPref("external_video_pass_folder_playlist", false)

    /**
     * When true, external HTTP video sessions use the previous random UUID token.
     * Default false: derive a stable opaque SHA-256 token so external players can match
     * the same playback URL for resume.
     */
    val externalVideoRandomizeToken = boolPref("external_video_randomize_token", false)
        .observed(::updateWhenExternalVideoRandomizeTokenChanges)

    /**
     * Private per-install salt for stable external HTTP session tokens. Generated lazily;
     * never shown in URLs or settings.
     */
    val externalVideoTokenSalt = stringPref("external_video_token_salt", "")

    /**
     * Loopback port reused while stable external video URLs are enabled.
     * Zero until the first successful bind chooses and persists a port.
     */
    val externalVideoStablePort = intPref("external_video_stable_port", 0)

    /**
     * When true, folder-view shows folder galleries below [browseSmallGalleryMinPages].
     * Default false: UI hides those rows (scanner listing is unchanged).
     */
    val browseShowSmallGalleries = boolPref("browse_show_small_galleries", true)

    /**
     * Folder top-bar: show rows tagged [BrowseEntry.hidden] (dot names / `.nomedia` dirs).
     * Default off — UI hides them; lazy scan still tags and skips deep-scan when off.
     */
    val browseShowHiddenFiles = boolPref("browse_show_hidden_files", false)

    /**
     * Folder top-bar: show lazy-scan promoted `@…` galleries/videos/dirs.
     * Default on. When off, hide promotions and show [DirPresence.PromotedShell] real dirs.
     */
    val browseShowVirtualGalleries = boolPref("browse_show_virtual_galleries", true)

    /**
     * Privacy: include hidden (dot / `.nomedia`) trees in the **library** SAF/FS scanner.
     * Default off. Toggling triggers a library rescan.
     */
    val scanHiddenFiles = boolPref("scan_hidden_files", false)

    /**
     * When true (default), favourited directories are listed first in the Directories section
     * of folder browsers (order within each group is unchanged). Top-bar view menu toggle.
     */
    val browseFavoritesOnTop = boolPref("browse_favorites_on_top", true)

    /**
     * Minimum image count for a folder gallery when [browseShowSmallGalleries] is off.
     * Galleries with fewer pages are hidden in the UI only. Default 3.
     */
    val browseSmallGalleryMinPages = intPref("browse_small_gallery_min_pages", 3)

    /**
     * When true, keep the main NavigationBar/Rail visible on nested Browse/History folder
     * screens and Settings children so re-tapping the tab returns to that root.
     * When false (default on phones), hide the bar on those screens and use the
     * “Back to browse/history” FAB instead. Large screens always use a rail and never the FAB.
     */
    val persistMainNav = boolPref("persist_main_nav", false)

    /**
     * When true, hide the “Back to Browse/History/Library” FAB on folder screens and make the
     * top-bar back button run that same jump instead of climbing one path segment.
     * Default false (current: FAB when main nav is hidden; top-bar back = go up).
     */
    val hideBackToFab = boolPref("hide_back_to_fab", false)
    val listThumbSize = intPref("list_tile_size", 40)
    val detailSize = intPref("detail_size_2", 0)
    val thumbColumns = intPref("thumb_columns", 3)

    /**
     * When false, SMB/WebDAV **folder gallery** image covers are not downloaded
     * (already-cached thumbs still show). Useful for remote / metered access.
     * Default true (download covers as usual).
     *
     * Archive/document covers (ZIP/RAR/7z/PDF/EPUB first page) use [downloadNetworkArchiveThumbs].
     */
    val downloadRemoteThumbs = boolPref("download_remote_thumbs", true)

    /**
     * When false, SMB/WebDAV **archive/document** browse thumbs are not extracted over the network
     * (cached JPEG / solid_extract / document_extract page 0 still show). Separate from
     * [downloadRemoteThumbs] so first-page extract can stay off without disabling folder image covers.
     * Default true.
     */
    val downloadNetworkArchiveThumbs = boolPref("download_network_archive_thumbs", true)

    /**
     * When false, SMB/WebDAV **video** browse thumbs are not extracted over the network
     * (already-cached JPEG in [com.hippo.ehviewer.library.VideoThumbnail] still shows).
     * Local video always uses disk cache extraction. Default true.
     */
    val downloadNetworkVideoThumbs = boolPref("download_network_video_thumbs", true)

    /**
     * Browse ZIP/CBZ like folders (listing / photo-grid / DirectoryListing classify).
     * Off = always open zip/cbz as archive reader. Default on. Settings → General / browse menu.
     */
    val browseZipAsDir = boolPref("browse_zip_as_dir", true)

    /**
     * Default folder-gallery open gesture.
     * On: tap → photo-grid virtual folder; long-press → reader.
     * Off (default): tap → reader; long-press → photo-grid virtual folder.
     */
    val photoGridMode = boolPref("photo_grid_mode", false)

    /**
     * When opening a photo-grid virtual folder, scroll the grid to the last reader page
     * (same progress gid as the folder reader). Off keeps the last photo-grid scroll position.
     * Default on.
     */
    val photoGridScrollToProgress = boolPref("photo_grid_scroll_to_progress", true)

    /**
     * Allow downloading SMB/WebDAV images for photo thumbs (photo-grid virtual folder **and**
     * Folder-mode image files; decode to small JPEG like browse covers). Cached thumbs still
     * show when off. Default true.
     */
    val downloadNetworkPhotoGridThumb = boolPref("download_network_photo_grid_thumb", true)

    /**
     * When downloading a network image thumb (gallery cover, photo-grid, or Folder-mode
     * image): also store the **original** full file in page cache (`smb_cache` /
     * `webdav_cache`) as the gallery first pic. Thumbs always go to `*_thumb_cache`.
     * Independent of the download-thumb toggles. Pref key kept for upgrades.
     */
    val saveThumbOriginalCache = boolPref("save_photo_grid_original_cache", false)

    val showGalleryPages = boolPref("show_gallery_pages", true)
    val showReadingProgress = boolPref("show_reading_progress", false)

    /**
     * When true (default), app start runs [com.hippo.ehviewer.library.LocalLibrary.startupMaintenance]
     * (prune inaccessible galleries; MediaStore sources also rescan).
     */
    val libraryStartupScan = boolPref("library_startup_scan", true)

    /**
     * Library favourites strip. Keys:
     * `local:{rootId}`, `smb:{id}`, `webdav:{id}`,
     * `gallery:{galleryId}`,
     * `lf:{rootId}:{rel}`, `sf:{sourceId}:{rel}`, `wf:{sourceId}:{rel}`.
     */
    val favoriteBrowseSources = stringSetPref("favorite_browse_sources", emptySet())

    /**
     * Optional cover keys for folder favourites (`lf:` / `sf:` / `wf:`).
     * Entries: `{favKey}\u0001{thumbKey}` where thumbKey is a local absolute path or
     * [com.hippo.ehviewer.library.HistoryThumbKey] (`smb-thumb:` / `dav-thumb:`).
     */
    val favoriteBrowseThumbs = stringSetPref("favorite_browse_thumbs", emptySet())

    /**
     * Per-folder browse-mode persist. Entries: `{lf|sf|wf}:{id}:{rel}={prefInt}`.
     * See [com.hippo.ehviewer.library.BrowseModePersist].
     */
    val persistBrowseModes = stringSetPref("persist_browse_modes", emptySet())
    val showVoteStatus = boolPref("show_vote_status", false)
    val showComments = boolPref("show_gallery_comments", true)
    val commentThreshold = intPref("comment_threshold", -100)
    val showTagTranslations = boolPref("show_tag_translations", false).observed(::updateWhenTagTranslationChanges)
    val meteredNetworkWarning = boolPref("cellular_network_warning", false)
    val showJpnTitle = boolPref("show_jpn_title", false)
    val requestNews = boolPref("request_news", false).observed { updateWhenRequestNewsChanges() }
    val hideHvEvents = boolPref("hide_hv_events", false)

    // Download
    val mediaScan = boolPref("media_scan", false).observed(::updateWhenKeepMediaStatusChanges)

    /**
     * SMB concurrent connections **per host** (Advanced). Default: 3.
     * Shared by all sources on the same host:port; stays well under Win11 Pro’s ~20 inbound cap.
     */
    val multiThreadDownload = intPref("download_thread_2", 3)

    /** Prefer SMB 3.x only (disable SMB 2.0.2 / 2.1 dialects). Default off. */
    val smb3Only = boolPref("smb3_only", false).observed {
        com.hippo.ehviewer.smb.SmbGateway.onProtocolSettingsChanged()
    }

    /** Require SMB3 encryption when negotiating. Default off. */
    val smbEncryptData = boolPref("smb_encrypt_data", false).observed {
        com.hippo.ehviewer.smb.SmbGateway.onProtocolSettingsChanged()
    }

    /**
     * smbj async NIO transport (list / browse / video channel groups). Default on.
     * Off falls back to one blocking Packet Reader thread per TCP.
     * Toggle drops browse pools and sticky video/FUSE so the next op uses the new transport.
     */
    val smbAsyncTransport = boolPref("smb_async_transport", true).observed {
        com.hippo.ehviewer.smb.SmbGateway.onProtocolSettingsChanged()
    }
    val downloadDelay = intPref("download_delay_3", 1000)
    val timeoutSpeed = intPref("timeout_speed_level", 6)

    /** Source files/pages to download or extract ahead of the reader anchor. */
    val preloadImage = intPref("preload_image_2", 5)

    /**
     * Skip all SMB/WebDAV/stream-archive page writes to flash (prefetch and current
     * page). Decode from RAM; leftover files from earlier sessions may still be read.
     * Local folders already skip a page cache.
     */
    val disableReaderNetworkCache = boolPref("disable_reader_network_cache", true)

    /** Decoded images to keep ahead independently of [preloadImage]. */
    val readerDecodeAhead = intPref("pref_reader_decode_ahead", 3)

    /** Use one decoded page of lookahead for formats with expensive decode pipelines. */
    val readerAutoDecodeAhead = boolPref("pref_reader_auto_decode_ahead", true)
    val downloadOriginImage = boolPref("download_origin_image", false)
    val saveAsCbz = boolPref("save_as_cbz", false)
    val archiveMetadata = boolPref("archive_metadata", true)

    // Privacy
    val security = boolPref("require_unlock", false)
    val securityDelay = intPref("require_unlock_delay", 0)
    val enabledSecurity = boolPref("enable_secure", false)

    /**
     * Master history switch. When false, nothing is written and Privacy toggle clears
     * existing rows + device search history. Nested file/gallery prefs only apply when on.
     */
    val saveHistory = boolPref("save_history", true)

    /**
     * When [saveHistory] is on: record opened **files** (archives, stream archives,
     * videos, and other non-dir files, including library archive galleries). Default on.
     * Does not gate browse-dir history.
     */
    val saveFileHistory = boolPref("save_file_history", true)

    /**
     * When [saveHistory] is on: record opened **galleries** (library folder galleries and
     * browse folder-galleries). Default on. Does not gate browse-dir history.
     */
    val saveGalleryHistory = boolPref("save_gallery_history", true)

    /**
     * Back to upper directory when opening from History / Library / Favourites.
     * On: system back from reader or a dir pin walks the parent browse path.
     * Off (default): back returns to History/Library (or the prior stack), except
     * History folder pins when [historyDirBackToUpper] is on.
     * Turning this on also forces [historyDirBackToUpper] on (one-way follow).
     */
    val alwaysExitToDir = boolPref("always_exit_to_dir", false)

    /**
     * When [alwaysExitToDir] is off: folders opened from History still walk upper dirs
     * on back (default on). Hidden in UI while [alwaysExitToDir] is on; enabling
     * [alwaysExitToDir] turns this on and never auto-turns it off.
     */
    val historyDirBackToUpper = boolPref("history_dir_back_to_upper", true)

    /**
     * Library list pin: when true (default), recently opened galleries (HISTORY time)
     * float above the Name/Date secondary sort. Toggle lives in the Library view menu.
     */
    val libraryRecentOpen = boolPref("library_recent_open", true)

    /**
     * Library gallery secondary sort: 0 = name, 1 = date (latest image / archive file
     * mtime from scan). Combined with [libraryRecentOpen] pin. Default name.
     * (Legacy pref value 2 = old exclusive Last-open sort → treated as name.)
     */
    val librarySortMode = intPref("library_sort_mode", 0)

    /**
     * Per-file skip / failure notes (video thumb `.failed` and similar sidecars).
     * Lives in app data, not [android.content.Context.getCacheDir]. Default off.
     * Turning this off deletes existing markers.
     */
    val saveFileMarkers = boolPref("save_file_markers", true).observed(::updateWhenSaveFileMarkersChanges)

    // Advanced
    val saveParseErrorBody = boolPref("save_parse_error_body", true)
    val saveCrashLog = boolPref("save_crash_log", false)
    val readCacheSize = intPref("read_cache_size_2", 640)
    val enableCronet = boolPref("enable_cronet", true)
    val enableQuic = boolPref("enable_quic", true)

    /**
     * WebDAV: trust any TLS certificate / skip hostname verify (self-signed LAN HTTPS).
     * Default off — normal system trust for https://. Rebuilds [WebDavClient] on change.
     * Cleartext http:// is controlled by network security config (explicit http URLs only).
     */
    val webDavInsecureTls = boolPref("webdav_insecure_tls", false).observed {
        runCatching { com.hippo.ehviewer.webdav.WebDavClient.resetClient() }
    }

    /**
     * Slim FGS for external HTTP / streamdoc.
     *
     * **Default off (limited):** idle HTTP session + FGS **20 minutes**, then stop. Screen off
     * drops sticky sockets unless a transfer is in flight.
     *
     * **On (unlimited):** keep HTTP listener + session map until Recents swipe. No wake lock.
     * Screen off keeps the connection only while playing (background playback).
     */
    val streamKeepAliveUnlimited = boolPref("stream_keep_alive_unlimited", false)
        .observed(::updateWhenStreamKeepAliveUnlimitedChanges)

    val hardwareBitmapThreshold = intPref("hardware_bitmap_threshold", 16384)
    val preloadThumbAggressively = boolPref("preload_thumb_aggressively", false)
    val animateItems = boolPref("animate_items", true)
    val desktopSite = boolPref("desktop_site", true)

    // About
    val backupBeforeUpdate = boolPref("backup_before_update", false)

    // Default off: release channel uses public GitHub Releases (recommended for LocalViewer).
    // CI channel needs Actions artifact access and is for snapshot builds only.
    val useCIUpdateChannel = boolPref("ci_update_channel", false)
    val updateIntervalDays = intPref("update_interval_days", 7)

    // Misc
    val languageFilter = intPref("language_filter", -1)
    val downloadSortMode = intPref("download_sort_mode", 0)
    val downloadFilterMode = intPref("download_filter_mode", 0)
    val hasSignedIn = boolPref("has_signed_in", false)

    // Local viewer: never force EH sign-in
    val needSignIn = boolPref("need_sign_in", false)
    val gridView = boolPref("grid_view", false)
    val qSSaveProgress = boolPref("qs_save_progress", true)
    val displayName = stringOrNullPref("display_name")
    val avatar = stringOrNullPref("avatar")
    val recentDownloadLabel = stringOrNullPref("recent_download_label")

    var downloadScheme by stringOrNullPref("image_scheme")
    var downloadAuthority by stringOrNullPref("image_authority")
    var downloadPath by stringOrNullPref("image_path")
    var downloadQuery by stringOrNullPref("image_query")
    var downloadFragment by stringOrNullPref("image_fragment")
    var archivePasswds by stringSetPref("archive_passwds")
    var appLinkVerifyTip by boolPref("app_link_verify_tip", false)
    var hasDefaultDownloadLabel by boolPref("has_default_download_label", false)
    var removeImageFiles by boolPref("include_pic", true)
    var recentFavCat by intPref("recent_fav_cat", -2)
    var clipboardTextHashCode by intPref("clipboard_text_hash_code", 0)
    var requestNewsTime by intPref("request_news_time", 0).observed { updateWhenRequestNewsChanges() }
    var lastDawnDays by intPref("last_dawn_days", 0)
    var recentToplist by stringPref("recent_toplist", "11")
    var defaultDownloadLabel by stringOrNullPref("default_download_label")
    var lastUpdateTime by longPref("last_update_time", BuildConfig.COMMIT_TIME)

    // Reader
    val cropBorder = boolPref("crop_borders", false)
    val colorFilter = boolPref("pref_color_filter_key", false)
    val colorFilterValue = intPref("color_filter_value", 0)
    val colorFilterMode = intPref("color_filter_mode", 0)
    val customBrightness = boolPref("pref_custom_brightness_key", false)
    val customBrightnessValue = intPref("custom_brightness_value", 0)
    val readingMode = intPref("pref_default_reading_mode_key", ReadingModeType.WEBTOON.prefValue)
    val orientationMode = intPref("pref_default_orientation_type_key", OrientationType.DEFAULT.prefValue)
    val showReaderSeekbar = boolPref("pref_show_reader_seekbar", true)
    val showPageNumber = boolPref("pref_show_page_number_key", true)

    /** Hide reader title/top app bar (bottom bar + seekbar still show when chrome is visible). Default on. */
    val readerHideTopBar = boolPref("pref_reader_hide_top_bar", true)

    /** Last open tab in the reader settings bottom sheet (0=mode, 1=general, 2=filter). */
    val readerSettingsTab = intPref("pref_reader_settings_tab", 0)
    val readerTheme = intPref("pref_reader_theme_key", 1)

    /** Off = double-tap prev/next gallery (folder mode). Default: off. */
    val doubleTapToZoom = boolPref("pref_double_tap_to_zoom", false)

    /**
     * Fit-rotate mode: 0=off, 1=CW, 2=CCW ([eu.kanade.tachiyomi.ui.reader.setting.AutoRotateMode]).
     * Default CW (matches previous auto-on + clockwise).
     */
    val autoRotateMode = intPref("pref_auto_rotate_mode", 0)

    /**
     * Coil decode size vs shorter screen edge:
     * 0=1.5x, 1=2x, 2=2.5x, 3=3x, 4=original
     * ([eu.kanade.tachiyomi.ui.reader.setting.DecodeSizeType]). Default 1.5x.
     * One-shot full-res: page menu "View original image".
     */
    val readerDecodeSize = intPref("pref_reader_decode_size", 0)

    /**
     * Cap SMB pool for safer original-size reading: 3 TCP sessions, 1 op/session.
     * Overrides Advanced concurrent-connection count while enabled.
     */
    val smbReaderSafeConcurrency = boolPref("pref_smb_reader_safe_concurrency", false).observed {
        runCatching { com.hippo.ehviewer.smb.SmbGateway.onReaderSafeConcurrencyChanged() }
    }

    /**
     * Prefer GPU hardware bitmaps in the reader.
     *
     * When on: Coil [allowHardware] for decode (no software intermediate for crop/QR —
     * those stay off). If decode still returns software (size policy / format / OEM),
     * [com.hippo.ehviewer.coil.HardwareBitmapInterceptor] upgrades under
     * [hardwareBitmapThreshold]. Gain maps stay software ([Bitmap.copy] strips them).
     * Default on.
     */
    val readerHardwareBitmap = boolPref("pref_reader_hardware_bitmap", true)

    /**
     * Lib stills (JXL / JXR / PQ-AVIF): decode to Bitmap and skip Ultra HDR JPEG convert.
     * Default off = convert + Coil (deep color reduced; WCG only as encode tags).
     * When on: [com.hippo.ehviewer.image.hdr.LibDirectDecode]; with [readerAdvancedColor]
     * preserves P3/BT.2020 + F16 where useful; advanced off rematrixes wide→709.
     * Network/SMB/WebDAV keep original when on. Browse covers still convert to small JPEG.
     */
    val readerLibDirectBitmap = boolPref("pref_reader_lib_direct_bitmap", false)

    /**
     * Window HDR presentation only: when a composed page has a gain map and the
     * display supports HDR, set [android.view.Window.setColorMode] to
     * [android.content.pm.ActivityInfo.COLOR_MODE_HDR].
     *
     * Does **not** disable convert (JXR/PQ/JXL → Ultra HDR) or gain-map decode —
     * those always run so files open; off = SDR base presentation without window HDR.
     */
    val readerHdrDisplay = boolPref("pref_reader_hdr_display", true)

    /**
     * OPPO / OnePlus / realme **ProXDR** HEIC: after platform HEIC decode, attach
     * [android.graphics.Gainmap] from the proprietary trailer (same present path as
     * Ultra HDR / gain-map AVIF — **no** UHDR JPEG convert). Off = SDR base only.
     * Experimental — boost math is reverse-engineered from OEM samples.
     */
    val readerOppoProxdr = boolPref("pref_reader_oppo_proxdr", false)

    /**
     * Platform high bit depth for **PNG/APNG** under [readerAdvancedColor].
     *
     * When on **and** advanced color is on: bypass Coil hardware-direct for high-depth
     * PNG → [BitmapFactory] preferred [Bitmap.Config.RGBA_F16] in linear extended sRGB,
     * then optional FP16 [HardwareBuffer] wrap. The decoder maps embedded WCG profiles into
     * extended scRGB values without a post-decode pixel loop. AVIF/HEIF stay on the normal
     * platform path. Default off (2× RAM). When
     * WCG/[readerAdvancedColor] changes, this value is forced to match (WCG drives
     * sub-toggle; sub-toggle never drives WCG).
     */
    val readerPlatformHighDepth = boolPref("pref_reader_platform_high_depth", false)

    /**
     * Reader wide color + high bit depth master (default **on**).
     *
     * When on and the display supports WCG:
     * - Window [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT] for the reader session
     *   (HDR still wins while HDR pages are composed).
     * - Platform stills: ImageDecoder keeps embedded ICC (sRGB stays sRGB-tagged;
     *   P3 stays P3 — no forced target ColorSpace).
     * - Lib-direct: preserve P3/BT.2020 and extra F16 for SDR-709.
     * - Drives [readerPlatformHighDepth] one-way: every WCG change copies into the
     *   platform-HBD sub-toggle (user may still turn HBD off while WCG stays on).
     *
     * When off: platform sRGB conversion; lib rematrix wide→709; no WCG window; HBD off.
     */
    val readerAdvancedColor = boolPref("pref_reader_advanced_color", true).observed { wcg ->
        // WCG always updates sub-toggle; sub-toggle must never write back to WCG.
        readerPlatformHighDepth.value = wcg
    }
    val fullscreen = boolPref("fullscreen", true)
    val cutoutShort = boolPref("cutout_short", true)
    val keepScreenOn = boolPref("pref_keep_screen_on_key", true)
    val readerLongTapAction = boolPref("reader_long_tap", true)
    val pageTransitions = boolPref("pref_enable_transitions_key", true)
    val readWithVolumeKeys = boolPref("reader_volume_keys", false)
    val readWithVolumeKeysInverted = boolPref("reader_volume_keys_inverted", false)
    val grayScale = boolPref("pref_grayscale", false)
    val invertedColors = boolPref("pref_inverted_colors", false)
    val readerWebtoonNav = intPref("reader_navigation_mode_webtoon", 0)
    val readerPagerNav = intPref("reader_navigation_mode_pager", 0)
    val readerPagerNavInverted = intPref("reader_tapping_inverted_2", 0)
    val readerWebtoonNavInverted = intPref("reader_tapping_inverted_webtoon_2", 0)
    val webtoonSidePadding = intPref("webtoon_side_padding", 0)
    val navigateToPan = boolPref("navigate_pan", true)
    val imageScaleType = intPref("pref_image_scale_type_key", 1)
    val landscapeZoom = boolPref("landscape_zoom", false)

    /**
     * Landscape dual-page behavior (not a reading mode).
     * - LTR/RTL/Vertical: two pages side-by-side; next advances one spread.
     * - Webtoon/continuous: horizontal continuous strip (no pairing).
     * - Vertical paged / portrait: unchanged.
     */
    val dualPageLandscape = boolPref("pref_dual_page_landscape", true)
    val zoomStart = intPref("pref_zoom_start_key", 1)
    val showNavigationOverlayNewUser = boolPref("reader_navigation_overlay_new_user", true)
    val showNavigationOverlayOnStart = boolPref("reader_navigation_overlay_on_start", false)
    val stripExtraneousAds = boolPref("strip_extraneous_ads", false)

    /**
     * Full-screen flash after paged (gallery) page turns to reduce E-Ink ghosting.
     * Webtoon / continuous vertical are unaffected.
     */
    val eInkRefreshEnabled = boolPref("pref_eink_refresh_enabled", false)

    /** Flash duration in ms (100–1500, typically stepped by 100). */
    val eInkRefreshDuration = intPref("pref_eink_refresh_duration", 100)

    /** Refresh every N page changes (1–10). */
    val eInkRefreshInterval = intPref("pref_eink_refresh_interval", 1)

    /** 0=black, 1=white, 2=white then black. */
    val eInkRefreshStyle = intPref("pref_eink_refresh_style", 0)

    init {
        edit { pref ->
            if ("CN" == Locale.getDefault().country) {
                if (showTagTranslations !in pref) pref[showTagTranslations] = true
            }
            val orientation = pref[orientationMode]
            if (OrientationType.entries.none { it.prefValue == orientation }) {
                pref.remove(orientationMode)
            }
        }
    }

    interface Delegate<R> {
        fun changesFlow(): Flow<Unit>
        operator fun getValue(thisRef: Any?, prop: KProperty<*>?): R
        operator fun setValue(thisRef: Any?, prop: KProperty<*>?, value: R)
    }

    private fun intArrayPref(key: String, count: Int) = object : Delegate<IntArray> {
        private val delegates = Array(count) { intPref("${key}_$it", 0) }
        override fun changesFlow(): Flow<Unit> = delegates.asFlow().flatMapMerge { it.changesFlow() }.conflate()
        override fun getValue(thisRef: Any?, prop: KProperty<*>?) = IntArray(delegates.size) { delegates[it].value }
        override fun setValue(thisRef: Any?, prop: KProperty<*>?, value: IntArray) {
            check(value.size == count)
            edit { pref -> value.zip(delegates) { v, d -> pref[d] = v } }
        }
    }

    private fun stringArrayPref(key: String, count: Int, defMetaValue: String) = object : Delegate<Array<String>> {
        private val delegates = Array(count) { stringPref("${key}_$it", "$defMetaValue $it") }
        override fun changesFlow(): Flow<Unit> = delegates.asFlow().flatMapMerge { it.changesFlow() }.conflate()
        override fun getValue(thisRef: Any?, prop: KProperty<*>?) = Array(delegates.size) { delegates[it].value }
        override fun setValue(thisRef: Any?, prop: KProperty<*>?, value: Array<String>) {
            check(value.size == count)
            edit { pref -> value.zip(delegates) { v, d -> pref[d] = v } }
        }
    }
}
