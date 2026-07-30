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
  <img src="https://github.com/zmz125000/LocalViewer-art/blob/master/launcher_icon-web.svg" width="200" alt="EhViewer">
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

基于 EhViewer 的高性能 Android SMB/WebDAV/LAN 图片查看器和漫画阅读器，多级文件夹智能分类，缩略图，看图双击跳转前后相册，最大两亿像素原图显示。

致敬 Perfect Viewer 和 Kuro Reader.  

采用 [Material Design 3](https://m3.material.io/) 并支持 [动态取色](https://m3.material.io/styles/color/dynamic-color/overview)。

# 功能特性

* 开源自由免费无广告
* 原生安卓应用 (Kotlin + Jetpack Compose)
* 基于 EhViewer 带预加载和本地缓存的高性能阅读器
* Webtoon 条漫模式
* 根据屏幕尺寸自动旋转图片
* 双击跳转到下一个文件夹
* 隐私模式和历史记录
* ZIP/RAR/CBZ/CBR/CBT/PDF/EPUB 格式支持.
* 高性能 smbj 客户端，支持并发连接，图片丝滑加载
* Ktor CIO WebDAV client 客户端 支持 HTTP/1.1 和 TLS.
* // Cronet WebDAV 客户端，支持 HTTP/2 和 QUIC
* HQ 模式支持原图解码显示 (HW 位图最大支持两亿像素)
* 自适应 Material Design 3 导航条和侧边栏
* 针对深层文件夹路径优化的导航流程
* **智能混合加载相册目录和子文件夹，默认加载 SMB 相册封面**
* 支持 SMB3 加密
* 支持 EasyTier (移植自 [moonlight-vplus](https://github.com/qiin2333/moonlight-vplus))


# 下载

| Flavor      | Minimum Android Version | Notes                          |
|-------------|-------------------------|--------------------------------|
| Default     | 12                      | Full support                   |
| EasyTier    | 12 (arm64-v8a)          | Full support                   |


<a href="https://github.com/zmz125000/LocalViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/zmz125000/LocalViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

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

# 许可证

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
