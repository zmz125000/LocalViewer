<p align="right">
  <a href="/README.md">
  English
  </a>
  <span> | </span>
  <strong>简体中文</strong>
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
    <a href="#描述">
    描述
    </a>
    <span> | </span>
    <a href="#下载">
    下载
    </a>
    <span> | </span>
    <a href="#截图">
    截图
    </a>
    <span> | </span>
    <a href="#感谢">
    感谢
    </a>
    <span> | </span>
    <a href="#许可证">
    许可证
    </a>
  </h3>
</div>

# 描述

高性能 Android SMB/WebDAV 图片查看器/漫画阅读器，支持网络图库文件夹。

基于 [EhViewer](https://github.com/FooIbar/EhViewer)   
采用 [Material Design 3](https://m3.material.io/)  
并支持 [动态取色](https://m3.material.io/styles/color/dynamic-color/overview)。

类似 Perfect Viewer 和 Kuro Reader，但支持高分辨率图片（不降采样）、简洁界面和更好的性能。

使用 Grok 4.5 构建。

## 功能特性
* 开源自由免费无广告。
* Webtoon 条漫阅读器。
* 本地与网络照片/视频/漫画/电子书库。
* 简单易用免配置，添加文件夹即可开始阅读。
* 即时扫描并分类本地和网络文件夹中的媒体文件。
* 统一文件夹浏览（视频/照片/文档各自带有标签和筛选，互不干扰）。
* 像文件管理器一样打开或分享文件。
* 漫画图库文件夹封面和阅读进度。
* 图片预加载和内存调优，加载大图不崩溃
* 原生 Android 应用（Kotlin + Jetpack Compose）。
* Material Design 3 导航栏。
* 高度优化的网络图片加载，高质量渲染。
* 支持 HDR、广色域和 10 位色深。
* 通过网络共享支持 ZIP/RAR/CBZ/CBR/CBT/PDF/EPUB。
* 支持 PDF/EPUB/MOBI/FB2/TXT/Markdown 电子书及文本排版。
* 支持 JXL/JXR/JPG/AVIF/HEIC HDR。
* 兼容 Oppo/OnePlus ProXDR HEIC 格式。
* 通过内置 HTTP 服务器在浏览器中打开离线 HTML 网站档案。
* 网络文件夹播放，支持 MPV/MX Player/VLC，含字幕和外挂音轨。
* 优化的 Async TCP 连接池。
* 高性能 smbj 客户端，支持并发连接。
* SMB 签名 JCE AESCMAC 硬件加速。
* Ktor OkHttp WebDAV 客户端，默认 HTTP/2，并回退到 CIO HTTP/1.1。
* 来自 EhViewer 的高性能阅读器，带网络缓存。
* 阅读器双击打开上一个/下一个图库。
* 阅读器允许全尺寸图片解码。
* 阅读器自动旋转图片。
* 阅读器漫画双页模式。
* 墨水屏模式支持（移植自 [venera-next](https://github.com/cyrilpeng/venera-next)）。
* EasyTier 支持（移植自 [moonlight-vplus](https://github.com/qiin2333/moonlight-vplus)）。

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

# 下载

| 变体       | 最低 Android 版本          | 备注   |
|----------|--------------------------|------|
| Default  | 12                       | 完整支持 |
| EasyTier | 12 (arm64-v8a)          | 完整支持 |
| HDR      | 14 (arm64-v8a, x86-64)  | 完整支持 |

<a href="https://github.com/zmz125000/LocalViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/zmz125000/LocalViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# 截图

![screenshots-01](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-01.webp)
![screenshots-02](https://github.com/zmz125000/LocalViewer-art/blob/master/screenshots-02.webp)

# 感谢

本项目受到了诸多开源项目的帮助

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

**应用库**

- [smbj](https://github.com/hierynomus/smbj) — SMB 客户端
- [ZXing](https://github.com/zxing/zxing) — 二维码
- [Telephoto](https://github.com/saket/telephoto) — 可缩放图片
- [MaterialKolor](https://github.com/jordond/materialkolor) — 动态取色
- [Material Motion](https://github.com/fornewid/material-motion-compose) — 转场动画
- [Compose Preference](https://github.com/zhanghai/ComposePreference) — 设置界面
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) — 许可证页面
- [moko-resources](https://github.com/icerockdev/moko-resources) — 字符串
- [Reorderable](https://github.com/Calvin-LL/Reorderable) — 拖拽排序列表
- [xmlutil](https://github.com/pdvrieze/xmlutil) — XML
- [kotlin-multiplatform-diff](https://github.com/petertrr/kotlin-multiplatform-diff) — 文本差异
- [Splitties](https://github.com/LouisCAD/Splitties) 和 [Okio](https://square.github.io/okio/)

**原生编解码器**

- [libwebp](https://github.com/webmproject/libwebp)
- [libjxl](https://github.com/libjxl/libjxl)
- [libavif](https://github.com/AOMediaCodec/libavif) 和 [dav1d](https://code.videolan.org/videolan/dav1d)
- [jpegxr](https://github.com/bvibber/jpegxr)
- [OpenJPEG](https://github.com/uclouvain/openjpeg)
- [XZ](https://github.com/tukaani-project/xz) 和 [Nettle](https://gitlab.com/gnutls/nettle)


# 许可证

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    LocalViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    LocalViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
