State Mind Launcher  @VERSION@  便携版（ZIP）
========================================

解压即用，不需要安装，不在系统里留东西 —— 可以直接丢进 U 盘带着走。

怎么用
------
1. 把整个 State-Mind-Launcher-@VERSION@ 文件夹解压出来（**别解压到 C:\Program Files 里面**，
   那个位置普通权限写不了文件，便携模式会失败）。桌面、D 盘、U 盘都行。
2. 双击 `State Mind Launcher.exe`。第一次启动会在同目录自动建好 `data\` 文件夹。
3. 自带的 Java 运行时已经打包在里面了，这台电脑没装 Java 也能跑。

数据放在哪
----------
全部在当前文件夹里，不会碰 `%APPDATA%`：

    State-Mind-Launcher-@VERSION@\
        State Mind Launcher.exe   启动器本体
        app\  runtime\            程序与自带 JRE，不用动
        portable.txt              便携模式开关（内容就是说明，别删）
        data\
            .minecraft\           游戏版本、模组、存档、资源包
            settings.properties   启动器设置（Java 选择等）
            accounts.txt          账号

备份 / 换电脑
-------------
- **备份**：把整个文件夹复制一份就是完整备份（游戏数据和设置都在里面）。
- **换电脑**：整个文件夹拷到 U 盘，插到另一台电脑双击 exe，接着玩。
- **只搬游戏数据**：把 `data\.minecraft` 拷到新机器的同位置即可。

想改回「装在系统里」的行为
--------------------------
删掉 `portable.txt`，启动器就会回到安装版逻辑：数据写 `%APPDATA%\StateMind`。
（已经产生在 `data\` 里的东西不会自动搬过去，需要自己挪。）

首次运行提示
------------
安装包没有花钱买代码签名证书，Windows SmartScreen 可能会拦一下。
点「更多信息」→「仍要运行」即可。
