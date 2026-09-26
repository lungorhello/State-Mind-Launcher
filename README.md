# State Mind Launcher

Windows 上的 Minecraft 启动器。Kotlin + JavaFX 编写，界面全部由代码构建（不使用 FXML）。

**当前版本：0.2.6.0** —— 仍在开发中，功能尚不完整，见下方「现在还缺什么」。

## 功能现状

| 功能 | 状态 |
| --- | --- |
| 启动游戏 | 可用。按版本 json 拼装 JVM 与游戏参数，启动前校验 Java 版本 |
| 版本隔离 | 可用。每个版本独立的 `mods` / 存档 / 配置，互不干扰 |
| 离线账号 | 可用 |
| 第三方外置登录 | 可用。Yggdrasil 协议 + authlib-injector 注入，令牌本地持久化，失效时启动前弹窗续登 |
| 便携模式 | 可用。解压即用，数据写在包内 `data\`，不碰 `%APPDATA%` |
| Java 管理 | 可用。自动查找本机 Java，也可手动指定 |
| 实例管理 | 半成品。能扫描目录、识别加载器与游戏版本，但卡片仅作展示，从这里启动还未接通 |
| 微软（正版）登录 | 未开始 |
| 下载游戏 / 版本 / 加载器 / 整合包 | 未开始（「下载」页为空壳） |
| 「探索」页 | 未开始（为空壳） |

## 使用前提

启动器**目前不会下载游戏本体**。使用前需要一份已经装好的 Minecraft，把它的 `.minecraft` 目录放到数据根目录下：

```
%APPDATA%\StateMind\.minecraft\
```

游戏版本放在 `<该目录>\versions\` 下，每种加载器（Fabric / Forge / NeoForge / Quilt / OptiFine 等）只要具备标准的版本 json 与 `libraries` 结构即可被识别。

## 数据放在哪

| 运行方式 | 数据根目录 |
| --- | --- |
| 安装版 | `%APPDATA%\StateMind\` |
| 便携版 | 解压目录下的 `data\`（目录里存在 `portable.txt` 即为便携模式） |

根目录里的文件：

```
accounts.txt        账号列表
auth.json           第三方登录的令牌（只存 accessToken，不存密码）
gamedirs.txt        实例页里添加的游戏目录
settings.properties 启动器设置
authlib-injector.jar 外置登录所需的注入器（首次使用外置登录时自动下载）
.minecraft\         游戏目录
```

## 从源码运行

环境要求：**JDK 25**（Gradle toolchain 会用到，本机装了 JDK 25 即可，不必是默认 JDK）。JavaFX 与其余依赖由 Gradle 自动拉取，无需另外安装。

```
gradlew run
```

## 打包

```
gradlew packageDist
```

产物在 `build/jpackage/out/`：一个 MSI 安装包和一个便携版 ZIP。

打 MSI 需要 **WiX 5**：

```
dotnet tool install --global wix
wix extension add --global WixToolset.Util.wixext
wix extension add --global WixToolset.UI.wixext
```

打包参数（产品名、版本号、UpgradeCode、自定义卸载对话框）集中在 `packaging/` 目录，日常开发用不到。发布版本号改 `packaging/package.gradle.kts` 顶部的 `distAppVersion`。

## 目录结构

```
src/main/kotlin/org/example/statemind/
├── App.kt              主窗口、页面装配、启动流程编排
├── Main.kt             程序入口
├── core/               与界面无关的逻辑
│   ├── LaunchUtil.kt       拼装启动命令并拉起进程
│   ├── GameDir.kt          数据根目录、游戏目录与版本隔离
│   ├── InstanceScan.kt     扫描版本目录，识别加载器与游戏版本
│   ├── JavaStore.kt        本机 Java 的查找与记录
│   ├── Prefs.kt            启动器设置
│   ├── Account.kt / AccountStore.kt   账号模型与存储
│   ├── Portable.kt         便携模式探测
│   └── auth/               第三方外置登录
│       ├── YggdrasilApi.kt     协议层（认证 / 刷新 / 校验）
│       ├── AuthServer.kt       皮肤站地址解析
│       ├── AuthStore.kt        令牌存储
│       ├── AuthlibInjector.kt  注入器获取与启动参数
│       └── LaunchAuth.kt       把账号翻译成启动凭证
├── ui/                 界面（全代码构建）
│   ├── NavBar.kt / NavIcons.kt 导航栏与矢量图标
│   ├── Dialogs.kt              内置弹窗
│   ├── ThirdPartySignIn.kt     第三方登录动作（新增账号与续登共用）
│   └── page/                   各页面
└── packaging/          打包脚本与 WiX 模板
```

## 用到的第三方组件

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [authlib-injector](https://github.com/yushijinhun/authlib-injector) | 外置登录必需，运行时自动下载，**不随本仓库分发** | GPL-3.0 |
| [Atlantafx](https://github.com/mkpaz/atlantafx) | 界面主题 | MIT |
| [JavaFX](https://openjfx.io/) | 界面框架 | GPL-2.0 with Classpath Exception |
| 「游戏图标包 v1.4」 | 导航栏的 4 个矢量图标 | CC0 1.0 |

## 已知问题

- 安装包没有代码签名，首次运行会被 SmartScreen 拦一下，点「更多信息 → 仍要运行」即可。
- 控制台可能出现 JavaFX 的 unnamed module 警告，无实际影响。

## 许可证

[GPL-3.0](LICENSE)
