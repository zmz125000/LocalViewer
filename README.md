<p align="right">
  <strong>English</strong>
  <span> | </span>
  <a href="/docs/README/zh-cn.md">
  简体中文
  </a>
  <span> | </span>
  <a href="/docs/README/zh-tw.md">
  正體中文
  </a>
  <span> | </span>
  <a href="/docs/README/ja.md">
  日本語
  </a>
</p>

<h1 align="center">
  <img src="https://github.com/zmz125000/LocalViewer-art/blob/master/ic_launcher-playstore.webp" width="200" alt="EhViewer">
  <br>LocalViewer<br>
</h1>

<p align="center">
  <a href="https://github.com/zmz125000/LocalViewer/actions/workflows/ci.yml">
    <img src="https://github.com/zmz125000/LocalViewer/actions/workflows/ci.yml/badge.svg" alt="Github Actions">
  </a>
  <a href="/LICENSE">
    <img src="https://img.shields.io/github/license/zmz125000/LocalViewer" alt="LICENSE">
  </a>
  <a href="https://www.codefactor.io/repository/github/zmz125000/LocalViewer">
    <img src="https://www.codefactor.io/repository/github/zmz125000/LocalViewer/badge" alt="CodeFactor">
  </a>
  <a href="https://github.com/zmz125000/LocalViewer/releases">
    <img src="https://img.shields.io/github/v/release/zmz125000/LocalViewer" alt="Release">
  </a>
  <a href="https://github.com/zmz125000/LocalViewer/issues">
    <img src="https://img.shields.io/github/issues/zmz125000/LocalViewer" alt="Issues">
  </a>
</p>
<p align="center">
  <a href="https://github.com/zmz125000/LocalViewer/releases/latest">
    <img src="https://img.shields.io/github/downloads/zmz125000/LocalViewer/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat" alt="Github Actions">
  </a>
  <a href="https://github.com/zmz125000/LocalViewer/releases">
    <img src="https://img.shields.io/github/downloads/zmz125000/LocalViewer/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat" alt="LICENSE">
  </a>
</p>

<div align="center">
  <h3>
    <a href="#description">
    Description
    </a>
    <span> | </span>
    <a href="#download">
    Download
    </a>
    <span> | </span>
    <a href="#screenshot">
    Screenshot
    </a>
    <span> | </span>
    <a href="#thanks">
    Thanks
    </a>
    <span> | </span>
    <a href="#license">
    License
    </a>
  </h3>
</div>

# Description

A High performance Android SMB/WebDAV image viewer/comic reader with network gallery folder support.

Based on [EhViewer](https://github.com/FooIbar/EhViewer)   
With [Material Design 3](https://m3.material.io/)  
and [Dynamic Color](https://m3.material.io/styles/color/dynamic-color/overview) Support.  


Similar to Perfect Viewer and Kuro Reader but with Hi-Res images support (no downscaling), clean UI and way better performance.

Build with Grok 4.5.

## Features
* Webtoon gallery reader.
* Local and network libraries for photo/video/comic/ebook.
* Easy to use, just add your folders and done.
* Instant scanning and classification for media files in local and network folders.
* One for all folder listing mode (Videos/Photos/Documents all with their own tags and filters).
* Fully working on Quest 3.
* Open or share files like a file manager.
* Gallery folder covers and reading progress.
* Native Android app (Kotlin + Jetpack Compose).
* Material Design 3 Navigation bar.
* Highly optimized network image loading, with high-quality rendering.
* HDR, Wide Color Gamut and 10-bit color mode support.
* ZIP/RAR/CBZ/CBR/CBT/PDF/EPUB support over network share.
* PDF/EPUB/MOBI/FB2/TXT/Markdown Ebook support with text formatting.
* JXL/JXR/JPG/AVIF/HEIC HDR support.
* Compatible with Oppo/OnePlus ProXDR HEIC format.
* Open offline html website archive in your browser with built-in HTTP server.
* Network folder playback for MPV/MX Player/VLC with subtitles and external autio track. 
* Optimized Async TCP connection poll.
* Fast smbj client with concurrent connections.
* SMB signing JCE AESCMAC hardware acceleration.
* Ktor OkHttp WebDAV client with HTTP/2 (default) and CIO HTTP/1.1 fallback.
* High performance reader with network cache from EhViewer.
* Reader double tap to open previous/next gallery.
* Reader allow full size image decode.
* Reader auto rotate image.
* Reader comic dual pages mode.
* E-Ink mode support (ported from [venera-next](https://github.com/cyrilpeng/venera-next)).
* EasyTier support (ported from [moonlight-vplus](https://github.com/qiin2333/moonlight-vplus)).

### To use WebDAV

``openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes``  
```.\rclone.exe serve webdav "D:\" --addr :8443 --cert .\server.crt --key .\server.key --read-only --user admin --pass password```

### To use SMB3 encryption:
`Get-SmbShare | Select-Object Name, EncryptData`  
`Set-SmbShare -Name "Media" -EncryptData $true`   
`Set-SmbServerConfiguration -RejectUnencryptedAccess $false -Force`

```
while ($true) {
    Clear-Host
    $config = Get-SmbServerConfiguration
    $sessions = Get-SmbSession

    Write-Host "--- SMB SERVER ENCRYPTION STATUS ---" -ForegroundColor Cyan
    Write-Host "Global Server Encryption Enabled : $($config.EncryptData)"
    Write-Host "Reject Unencrypted Access       : $($config.RejectUnencryptedAccess)"
    Write-Host "Active Sessions                 : $(($sessions).Count)"
    Write-Host "Timestamp                       : $(Get-Date -Format 'HH:mm:ss')"
    Write-Host "------------------------------------`n"

    if ($sessions) {
        $sessions | Select-Object ClientComputerName, ClientUserName, Dialect, NumOpens | Format-Table -AutoSize
    }

    Start-Sleep -Seconds 1
}
```

# Download

| Flavor      | Minimum Android Version | Notes                          |
|-------------|-------------------------|--------------------------------|
| Default     | 12                      | Full support                   |
| EasyTier    | 12 (arm64-v8a)          | Full support                   |
| HDR         | 14 (arm64-v8a, x86-64)  | Full support                   |

<a href="https://github.com/zmz125000/LocalViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/zmz125000/LocalViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# Screenshot

![screenshots-01](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-01.webp)
![screenshots-02](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-02.webp)

# Thanks

Here is the libraries

- [Arrow](https://arrow-kt.io/)
- [AOSP & AndroidX](https://source.android.com/)
- [Kotlin & KotlinX](https://kotlinlang.org/)
- [Material Icons](https://github.com/google/material-design-icons)
- [Ktor](https://ktor.io/)
- [Coil](https://coil-kt.github.io/coil/)
- [Compose Destinations](https://composedestinations.rafaelcosta.xyz/)
- [libarchive](https://www.libarchive.org/)
- [libultrahdr](https://github.com/google/libultrahdr)
- [EasyTier](https://github.com/EasyTier/Easytier)

**App libraries**

- [smbj](https://github.com/hierynomus/smbj) — SMB client
- [ZXing](https://github.com/zxing/zxing) — QR codes
- [Telephoto](https://github.com/saket/telephoto) — zoomable images
- [MaterialKolor](https://github.com/jordond/materialkolor) — dynamic color
- [Material Motion](https://github.com/fornewid/material-motion-compose) — transitions
- [Compose Preference](https://github.com/zhanghai/ComposePreference) — settings UI
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) — license screen
- [moko-resources](https://github.com/icerockdev/moko-resources) — strings
- [Reorderable](https://github.com/Calvin-LL/Reorderable) — drag-to-reorder lists
- [xmlutil](https://github.com/pdvrieze/xmlutil) — XML
- [kotlin-multiplatform-diff](https://github.com/petertrr/kotlin-multiplatform-diff) — text diff
- [Splitties](https://github.com/LouisCAD/Splitties) and [Okio](https://square.github.io/okio/)

**Native codecs**

- [libwebp](https://github.com/webmproject/libwebp)
- [libjxl](https://github.com/libjxl/libjxl)
- [libavif](https://github.com/AOMediaCodec/libavif) and [dav1d](https://code.videolan.org/videolan/dav1d)
- [jpegxr](https://github.com/bvibber/jpegxr)
- [OpenJPEG](https://github.com/uclouvain/openjpeg)
- [XZ](https://github.com/tukaani-project/xz) and [Nettle](https://gitlab.com/gnutls/nettle)


# License

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    LocalViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    LocalViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
