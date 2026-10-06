<p align="right">
  <a href="/README.md">
  English
  </a>
  <span> | </span>
  <a href="/docs/README/zh-cn.md">
  简体中文
  </a>
  <span> | </span>
  <strong>正體中文</strong>
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
    <a href="#說明">
    說明
    </a>
    <span> | </span>
    <a href="#下載">
    下載
    </a>
    <span> | </span>
    <a href="#截圖">
    截圖
    </a>
    <span> | </span>
    <a href="#感謝">
    感謝
    </a>
    <span> | </span>
    <a href="#許可證書">
    許可證書
    </a>
  </h3>
</div>

# 說明

高效能 Android SMB/WebDAV 圖片檢視器／漫畫閱讀器，支援網路圖庫資料夾。

基於 [EhViewer](https://github.com/FooIbar/EhViewer)   
採用 [Material Design 3](https://m3.material.io/)  
並支援 [動態取色](https://m3.material.io/styles/color/dynamic-color/overview)。

類似 Perfect Viewer 和 Kuro Reader，但支援高解析度圖片（不降採樣）、簡潔介面與更好的效能。

使用 Grok 4.5 建置。

## 功能特性
* 開源自由免費無廣告。
* Webtoon 條漫閱讀器。
* 本機與網路照片／影片／漫畫／電子書庫。
* 簡單易用免設定，加入資料夾即可開始閱讀。
* 即時掃描並分類本機與網路資料夾中的媒體檔案。
* 統一資料夾瀏覽（影片／照片／文件各自帶有標籤與篩選，互不干擾）。
* 像檔案管理員一樣開啟或分享檔案。
* 漫畫圖庫資料夾封面與閱讀進度。
* 原生 Android 應用程式（Kotlin + Jetpack Compose）。
* Material Design 3 導覽列。
* 高度最佳化的網路圖片載入，高品質渲染。
* 支援 HDR、廣色域與 10 位元色深。
* 透過網路共用支援 ZIP/RAR/CBZ/CBR/CBT/PDF/EPUB。
* 支援 PDF/EPUB/MOBI/FB2/TXT/Markdown 電子書及文字排版。
* 支援 JXL/JXR/JPG/AVIF/HEIC HDR。
* 相容 Oppo/OnePlus ProXDR HEIC 格式。
* 透過內建 HTTP 伺服器在瀏覽器中開啟離線 HTML 網站檔案。
* 網路資料夾播放，支援 MPV/MX Player/VLC，含字幕與外掛音軌。
* 最佳化的 Async TCP 連線池。
* 高效能 smbj 用戶端，支援並行連線。
* SMB 簽章 JCE AESCMAC 硬體加速。
* Ktor OkHttp WebDAV 用戶端，預設 HTTP/2，並可回退至 CIO HTTP/1.1。
* 來自 EhViewer 的高效能閱讀器，帶網路快取。
* 閱讀器雙擊開啟上一個／下一個圖庫。
* 閱讀器允許全尺寸圖片解碼。
* 閱讀器自動旋轉圖片。
* 閱讀器漫畫雙頁模式。
* 電子墨水模式支援（移植自 [venera-next](https://github.com/cyrilpeng/venera-next)）。
* EasyTier 支援（移植自 [moonlight-vplus](https://github.com/qiin2333/moonlight-vplus)）。

### 使用 WebDAV

``openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes``  
```.\rclone.exe serve webdav "D:\" --addr :8443 --cert .\server.crt --key .\server.key --read-only --user admin --pass password```

### 使用 SMB3 加密：
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

# 下載

| 變種       | 最低 Android 版本           | 備註   |
|----------|---------------------------|------|
| Default  | 12                        | 完整支援 |
| EasyTier | 12 (arm64-v8a)           | 完整支援 |
| HDR      | 14 (arm64-v8a, x86-64)   | 完整支援 |

<a href="https://github.com/zmz125000/LocalViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/zmz125000/LocalViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# 截圖

![LocalViewer screenshot main page](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-01.webp)
![LocalViewer screenshot gallery reader](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-02.webp)
![LocalViewer screenshot window manager](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-03.webp)

# 感謝

本項目受到了諸多開源項目的幫助

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

**應用程式函式庫**

- [smbj](https://github.com/hierynomus/smbj) — SMB 用戶端
- [ZXing](https://github.com/zxing/zxing) — QR code
- [Telephoto](https://github.com/saket/telephoto) — 可縮放圖片
- [MaterialKolor](https://github.com/jordond/materialkolor) — 動態取色
- [Material Motion](https://github.com/fornewid/material-motion-compose) — 轉場
- [Compose Preference](https://github.com/zhanghai/ComposePreference) — 設定介面
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) — 授權畫面
- [moko-resources](https://github.com/icerockdev/moko-resources) — 字串
- [Reorderable](https://github.com/Calvin-LL/Reorderable) — 拖曳排序清單
- [xmlutil](https://github.com/pdvrieze/xmlutil) — XML
- [kotlin-multiplatform-diff](https://github.com/petertrr/kotlin-multiplatform-diff) — 文字差異
- [Splitties](https://github.com/LouisCAD/Splitties) 和 [Okio](https://square.github.io/okio/)

**原生編解碼器**

- [libwebp](https://github.com/webmproject/libwebp)
- [libjxl](https://github.com/libjxl/libjxl)
- [libavif](https://github.com/AOMediaCodec/libavif) 和 [dav1d](https://code.videolan.org/videolan/dav1d)
- [jpegxr](https://github.com/bvibber/jpegxr)
- [OpenJPEG](https://github.com/uclouvain/openjpeg)
- [XZ](https://github.com/tukaani-project/xz) 和 [Nettle](https://gitlab.com/gnutls/nettle)


# 許可證書

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    LocalViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    LocalViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
