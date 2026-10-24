# MFMC Launcher / MFCL

**MDFZ Minecraft Craft Launcher（MFMC Launcher）** 是一个基于 **HMCL（Hello Minecraft! Launcher）** 框架开发的 Minecraft 启动器。

> 本项目不是 HMCL 官方发布，与 HMCL 开发团队没有隶属关系。
> 上游项目：<https://github.com/HMCL-dev/HMCL>

## 基于 HMCL 框架

- 本项目基于 HMCL 的代码与架构开发，保留了 HMCL 的 GPLv3 许可证和版权声明。
- 上游 HMCL 的版权归 huangyuhui 及 HMCL 贡献者所有。
- 本项目新增的 MFMC 代码同样以 **GNU General Public License v3.0** 发布，完整许可证见 [LICENSE](LICENSE)。
- 修改与新增内容主要集中在：
  - `HMCL/src/main/java/org/jackhuang/hmcl/ui/mfmc/`：MFMC 新 UI。
  - `HMCL/src/main/java/org/jackhuang/hmcl/MfclPaths.java`：`~/.mfcl` 数据隔离。
  - `HMCL/src/main/resources/assets/css/mfmc-light.css`：浅色主题。
  - `HMCL/src/main/java/org/jackhuang/hmcl/ui/Controllers.java`、`Launcher.java`：MFMC 启动路径与警告处理。

## 功能概览

- 浅色三标签界面：**启动游戏 / 下载 / 设置**。
- 启动页：账号头像、启动按钮、版本列表、新闻/公告栏。
- 支持微软正版、离线、authlib-injector 账户。
- 支持将 `.zip` / `.mrpack` 整合包拖入窗口安装。
- 下载页：游戏版本、模组加载器、模组、整合包、资源包、光影、世界。
- 设置页：Java 管理、版本隔离、关于信息。
- 经典 HMCL UI 仍然保留：发布产物默认进入 MFMC UI；经典 UI 可通过 `-Dhmcl.ui=classic` 或 `./run-internal.sh` 使用。
- 数据默认隔离到 `~/.mfcl`，不读写原有的 `~/.hmcl`、`~/.local/share/hmcl` 或 `~/.minecraft`。

## 目录隔离

| 内容 | 默认路径 |
| --- | --- |
| 配置、状态、日志、缓存 | `~/.mfcl/` |
| 默认游戏目录 | `~/.mfcl/minecraft/` |
| 默认工作区游戏目录 | `~/.mfcl/minecraft/workspace/` |

可用以下属性覆盖：

```bash
-Dmfcl.home=<目录>    # 覆盖整个 MFMC 数据目录
-Dmfcl.dir=<目录>     # 只覆盖配置目录
```

## 构建与运行

要求 JDK 17 或更高版本。

```bash
./gradlew :HMCL:shadowJar
java -Dhmcl.ui=mfmc -jar HMCL/build/libs/HMCL-*.jar
```

在 Wayland 桌面上，MFMC 会默认给使用 SDL 的游戏设置 `SDL_VIDEODRIVER=x11`，让游戏运行在 XWayland 下并保留标题栏、拖动和最小化/关闭按钮。如需恢复原生 Wayland，可加：

```bash
-Dmfmc.game.sdl.video.driver=wayland
```

在 Windows 混合显卡笔记本上，MFMC 会自动为当前 Java 进程写入“高性能 GPU”图形首选项，重启启动器后生效。如需关闭该行为：

```bash
-Dmfmc.gpu.preference=off
# 或
MFMC_GPU_PREFERENCE=off
```

Windows 的 MFMC 模式默认不绘制整窗投影，可通过 `-Dhmcl.window.shadow=true` 恢复。

## 说明

当前仓库是 MFMC 内部研发与测试版本，不是 HMCL 官方发行版。MFMC 对启动器界面、数据目录和启动流程做了定制；如果遇到问题，请优先在 MFMC 仓库反馈，不要将问题归到 HMCL 官方项目。
