<p align="right">
  <a href="/README.md">
  English
  </a>
  <span> | </span>
  <a href="/docs/README/zh-cn.md">
  简体中文
  </a>
  <span> | </span>
  <a href="/docs/README/zh-tw.md">
  正體中文
  </a>
  <span> | </span>
  <strong>日本語</strong>
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
    <a href="#説明">
    説明
    </a>
    <span> | </span>
    <a href="#ダウンロード">
    ダウンロード
    </a>
    <span> | </span>
    <a href="#スクリーンショット">
    スクリーンショット
    </a>
    <span> | </span>
    <a href="#感謝">
    感謝
    </a>
    <span> | </span>
    <a href="#ライセンス">
    ライセンス
    </a>
  </h3>
</div>

# 説明

ネットワークギャラリーフォルダに対応した、高性能な Android SMB/WebDAV 画像ビューア／コミックリーダーです。

[EhViewer](https://github.com/FooIbar/EhViewer) をベースに、  
[Material Design 3](https://m3.material.io/) と  
[ダイナミックカラー](https://m3.material.io/styles/color/dynamic-color/overview) に対応しています。

Perfect Viewer や Kuro Reader に似ていますが、高解像度画像（ダウンスケールなし）、すっきりした UI、より優れたパフォーマンスを備えています。

Grok 4.5 でビルド。

## 機能
* オープンソース、自由、無料、広告なし。
* Webtoon ギャラリーリーダー。
* ローカルおよびネットワークの写真／動画／コミック／電子書籍ライブラリ。
* 設定不要で簡単。フォルダを追加すればすぐ読み始められます。
* ローカルおよびネットワークフォルダ内のメディアファイルを即座にスキャンして分類。
* 統一フォルダ閲覧（動画／写真／ドキュメントはそれぞれ独自のタグとフィルタを持ち、互いに干渉しない）。
* ファイルマネージャのようにファイルを開く、または共有。
* コミックギャラリーフォルダのカバーと読書進捗。
* ネイティブ Android アプリ（Kotlin + Jetpack Compose）。
* Material Design 3 ナビゲーションバー。
* 高度に最適化されたネットワーク画像読み込みと高品質レンダリング。
* HDR、広色域、10 ビットカラーモードに対応。
* ネットワーク共有上の ZIP/RAR/CBZ/CBR/CBT/PDF/EPUB に対応。
* テキスト整形付きの PDF/EPUB/MOBI/FB2/TXT/Markdown 電子書籍に対応。
* JXL/JXR/JPG/AVIF/HEIC HDR に対応。
* Oppo/OnePlus ProXDR HEIC 形式に対応。
* 組み込み HTTP サーバーで、オフライン HTML ウェブサイトのアーカイブをブラウザで開く。
* 字幕と外部音声トラック付きの、MPV/MX Player/VLC 向けネットワークフォルダ再生。
* 最適化された Async TCP 接続プール。
* 同時接続に対応した高速 smbj クライアント。
* SMB 署名の JCE AESCMAC ハードウェアアクセラレーション。
* Ktor OkHttp WebDAV クライアント。HTTP/2（既定）と CIO HTTP/1.1 フォールバック。
* EhViewer 由来のネットワークキャッシュ付き高性能リーダー。
* リーダーでダブルタップすると前／次のギャラリーを開く。
* リーダーでフルサイズ画像のデコードを許可。
* リーダーで画像を自動回転。
* リーダーのコミック見開きモード。
* 電子インクモード対応（[venera-next](https://github.com/cyrilpeng/venera-next) から移植）。
* EasyTier 対応（[moonlight-vplus](https://github.com/qiin2333/moonlight-vplus) から移植）。

### WebDAV の使い方

``openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes``  
```.\rclone.exe serve webdav "D:\" --addr :8443 --cert .\server.crt --key .\server.key --read-only --user admin --pass password```

### SMB3 暗号化の使い方：
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

# ダウンロード

| Flavor      | Minimum Android Version | Features                       |
|-------------|-------------------------|--------------------------------|
| Default     | 12                      | Basic                          |
| Default     | 14 (arm64-v8a, x86-64)  | HDR, GPU Shaders, RAW          |
| EasyTier    | 12 (arm64-v8a)          | Basic, EasyTier                |
| EasyTier    | 14 (arm64-v8a)          | HDR, GPU Shaders, RAW, EasyTier|

<a href="https://github.com/zmz125000/LocalViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/zmz125000/LocalViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# スクリーンショット

![LocalViewer screenshot main page](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-01.webp)
![LocalViewer screenshot gallery reader](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-02.webp)
![LocalViewer screenshot window manager](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-03.webp)

# 感謝

本プロジェクトは多くのオープンソースプロジェクトの助けを受けています

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

**アプリライブラリ**

- [smbj](https://github.com/hierynomus/smbj) — SMB クライアント
- [ZXing](https://github.com/zxing/zxing) — QR コード
- [Telephoto](https://github.com/saket/telephoto) — ズーム可能な画像
- [MaterialKolor](https://github.com/jordond/materialkolor) — ダイナミックカラー
- [Material Motion](https://github.com/fornewid/material-motion-compose) — トランジション
- [Compose Preference](https://github.com/zhanghai/ComposePreference) — 設定 UI
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) — ライセンス画面
- [moko-resources](https://github.com/icerockdev/moko-resources) — 文字列
- [Reorderable](https://github.com/Calvin-LL/Reorderable) — ドラッグで並べ替えるリスト
- [xmlutil](https://github.com/pdvrieze/xmlutil) — XML
- [kotlin-multiplatform-diff](https://github.com/petertrr/kotlin-multiplatform-diff) — テキスト差分
- [Splitties](https://github.com/LouisCAD/Splitties) と [Okio](https://square.github.io/okio/)

**ネイティブコーデック**

- [libwebp](https://github.com/webmproject/libwebp)
- [libjxl](https://github.com/libjxl/libjxl)
- [libavif](https://github.com/AOMediaCodec/libavif) と [dav1d](https://code.videolan.org/videolan/dav1d)
- [jpegxr](https://github.com/bvibber/jpegxr)
- [OpenJPEG](https://github.com/uclouvain/openjpeg)
- [XZ](https://github.com/tukaani-project/xz) と [Nettle](https://gitlab.com/gnutls/nettle)


# ライセンス

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    LocalViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    LocalViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
