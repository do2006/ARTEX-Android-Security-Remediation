<div align="center">

# ARTEX Android

**把 ARTEX 装进手机。后端、数据库、Python 全在 APK 里；应用服务仅监听本机。**

[![Repository](https://img.shields.io/badge/GitHub-do2006%2FARTEX--Android--Security--Remediation-181717?style=flat-square&logo=github)](https://github.com/do2006/ARTEX-Android-Security-Remediation)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#装)
[![Security](https://img.shields.io/badge/security-hardened-22C55E?style=flat-square)](docs/SECURITY_REMEDIATION.md)
[![License](https://img.shields.io/badge/license-MIT-4A5568?style=flat-square)](#)

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/banner-dark.webp">
  <img src="docs/images/banner-light.webp" alt="ARTEX Android 界面" width="100%">
</picture>

</div>

## 它是什么

ARTEX 是一个 AI 自主渗透测试系统。原版跑在服务器上，要装 Docker、配数据库、开端口。

这个项目把整摞东西塞进了一个 APK：

```
┌─────────────────────────────────────────┐
│  ARTEX 后端（Go 静态二进制，PIE）        │
│  PostgreSQL 18.6                         │
│  Python 3.14  +  impacket / pypykatz     │
│  bash  +  readline / ncurses / iconv     │
└─────────────────────────────────────────┘
              ↓ 全部跑在手机里
        WebView 打开 127.0.0.1:8787
```

没有服务器或容器。核心服务可离线启动；按需下载工具和 Skill 时需要网络。

## 先看这里

这是一个安全加固后的维护仓库，默认分支为 `security-hardening-20261123`。先阅读：

- [安装与日常使用](docs/USAGE.md)
- [从源码构建 APK](docs/BUILD.md)
- [安全边界与已知限制](docs/SECURITY_REMEDIATION.md)
- [可复现构建输入清单](docs/REPRODUCIBLE_INPUTS.md)

当前提交已通过 Java 主机回归测试、Android API 源码编译和静态配置检查；**尚未在真实 Android 设备上构建、安装或运行预编译后端**。请在受控设备上完成自己的发布前验证，不要把它当作已完成运行时安全认证。

## Responsible use

ARTEX Android is maintained by NightFall Technologies for legitimate security research, assessment, and administration. It is provided as-is and users operate it at their own discretion and responsibility. Use it only on systems, applications, accounts, and networks you own or have explicit permission to assess. NightFall Technologies does not guarantee fitness for a particular purpose and does not accept responsibility for unauthorized or unlawful use.

## 装

目前请从本仓库源码构建自己的已签名 APK，具体步骤在 [构建指南](docs/BUILD.md)。发布者提供经过设备验证的 APK 后，才应在本仓库的 Releases 页面下载。

| | |
|---|---|
| **系统** | Android 8.0+ |
| **首次启动** | 1-2 分钟（初始化 PostgreSQL） |
| **体积** | APK 60 MB，首次启动后约 250 MB |
| **工具** | 不放 APK 里，面板里按需下载 |
| **网络访问** | 后端只监听本机 `127.0.0.1:8787`，不提供局域网访问 |

## 用

| 手势 | 作用 |
|---|---|
| **双指左滑** | 打开右侧面板 —— 备份恢复 · 工具管理 · Skill 管理 |
| **双指右滑** | 打开网页侧栏 —— 任务、资产、发现、报告 |
| **返回键** | 先网页后退，退到底再按才退出 APP |
| **电脑访问** | 不支持；服务仅限手机本机 WebView |

完整说明见 **[docs/USAGE.md](docs/USAGE.md)**。

## 工具

32 个工具放在独立仓库，APK 里一个都没有 —— 需要哪个下哪个。清单固定到应用内审查过的不可变提交，下载要求精确的 GitHub Release 路径、HTTPS 443 和 SHA-256 校验。

```
资产测绘  subfinder  shuffledns  puredns  alterx  asnmap  cloudlist
          cdncheck  uncover  gau
探测指纹  httpx  dnsx  tlsx  naabu
内容发现  katana  ffuf  gobuster
漏洞扫描  nuclei  afrog  dalfox  gitleaks  interactsh-client
隧道穿透  gost  npc  chisel  suo5  frp  ligolo
工具箱    fscan  gogo
其他      notify
```

这样 APK 从 154 MB 降到 60 MB，工具也能独立更新。

## 改

| 想做什么 | 看哪里 |
|---|---|
| 自己构建并签名一个 APK | [docs/BUILD.md](docs/BUILD.md) |
| 安装、备份和工具使用 | [docs/USAGE.md](docs/USAGE.md) |
| 安全边界和限制 | [docs/SECURITY_REMEDIATION.md](docs/SECURITY_REMEDIATION.md) |
| 壳对后端做了哪些适配 | [docs/ADAPTATION.md](docs/ADAPTATION.md) |

> 壳里做了不少不显然的事：Android 没有 `resolv.conf`，所以 DNS 要自己注入；
> PostgreSQL 二进制硬编码了 Termux 路径，靠 31 字符等长替换绕过去；
> Android 10 起禁止执行私有目录二进制，所以 targetSdk 必须停在 27。
> 这些都在 [ADAPTATION.md](docs/ADAPTATION.md) 里。

## 上游

[RuoJi6/ARTEX](https://github.com/RuoJi6/ARTEX) · 本仓库对应提交 `a05a886`

<div align="center"><sub>ARTEX 后端与前端版权归上游作者所有</sub></div>
