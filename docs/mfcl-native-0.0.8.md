# MFCL 0.0.8：Rust 迁移边界与验收

## 本次真正迁移的职责

| 路径 | Rust 负责 | Java 保留 |
| --- | --- | --- |
| Modrinth 搜索 | 查询条件、facets、分页和排序参数规划 | URL 编码、HTTP、镜像、重试 |
| CurseForge 搜索 | 分类、排序、分页参数规划 | API key 注入、HTTP 和重试；不把 API key 送入原生进程 |
| Modrinth 版本元数据 | 原始 JSON 检查、文件选择、依赖类型映射和版本记录投影 | 日期/加载器类型兼容、现有领域对象构建 |
| Modrinth 分类 | 按项目类型筛选，保持上游顺序 | 分类句柄与现有 UI 集成 |
| 共享版本选择 | 游戏版本/加载器筛选、稳定日期排序 | 非 ASCII 大小写比较兼容、原有对象身份 |
| CurseForge 本地文件识别 | 两遍流式 MurmurHash2，64 KiB 工作缓冲区，文件变化检测 | 接受结果前复核 Unix 文件身份；不支持时走原有实现 |
| Modrinth 本地文件识别 | 大文件复用已有的原生稳定文件 SHA-1 检查 | 小文件与不支持的文件系统继续使用 Java |

这不是将整个启动器、安装器或下载任务调度器重写为 Rust。JavaFX、账号状态、
安装编排、兼容规则、最终文件发布等仍在 Java；既有 Rust 下载/认证/启动能力继续保留。
当前发布包仍只内置 Linux x86_64 原生二进制。没有交叉编译并假装验收过 Windows/macOS。

## 可维护性与兼容性

- Rust 新职责分别位于 `mfcl-native/src/addon_catalog.rs` 与 `curse_fingerprint.rs`。
- Java 领域适配集中在 `NativeAddonCatalog`；窄 RPC 接口放在 `NativeCatalogRpc`，不继续扩张旧的巨大 facade。
- 原生进程池用不可变 capability 集合替代约 30 个平行布尔字段。
- 每个方法的 capability 路由在一张表中；认证信息不会进入新的只读目录接口。
- 模组筛选 IPC 在 IO 线程执行，项目、搜索和加载器变化会使旧异步结果失效。
- 同一次 HTTP 响应在 Rust 失败后交给 Java，不重复发出 HTTP 请求。
- 保留原先的第一文件选择、未知发行类型、依赖映射、空日期优先及纳秒精度稳定排序行为。
- 有歧义/重复键/过大的元数据、不支持的文件身份或旧后端均可回退。
- 新路径开关：`-Dmfcl.native.catalog.enabled=false`。

## 测试记录

在 Linux x86_64 上执行：

| 测试 | 结果 |
| --- | --- |
| Rust 默认配置 debug 测试 | 111 项通过（18 单元 + 93 协议） |
| Rust release 配置测试 | 111 项通过 |
| Rust `auth-test-endpoints` 测试配置 | 111 项通过；不用于打包 |
| Java，提供实际 Rust 可执行文件并启用实时 Modrinth 测试 | 816 项：790 通过，26 跳过，0 失败 |
| Java，不配置测试用 Rust 可执行文件 | 816 项：643 通过，173 跳过，0 失败 |
| 真实 Modrinth Sodium 1.20.1 版本和分类 | 与现有 Java 解析结果一致 |
| 真实 Minecraft 1.20.1 安装与离线启动 | 通过；校验全部资源 SHA-1，Rust exec 启动到 blocks.png 图集初始化 |
| 真实 Minecraft 1.20.1 / Forge 47.2.0 安装与离线启动 | 通过；包含处理器执行、原生类路径及参数规划和图集初始化 |

真实游戏测试使用隔离临时实例、离线测试身份和软件渲染，没有修改用户实例或登录真实账号。
第二轮安装复用了首轮独立测试缓存，并保留完整校验。

没有为了让测试变绿而删除兼容性、安全、取消或协议测试。修复了两个过时的测试观测方式：

1. 资源任务测试现在比较批量与独立任务的计划哈希，不再假定所有任务都有单个文件 dependents。
2. 实装测试识别新版合并 classpath、v2 处理器和 Forge 数据暂存操作，不再只记录旧 RPC 方法名。

仓库全量 Checkstyle 仍有既存的短版权头、旧超长 facade 和旧测试空行等问题；
不能将全量 Checkstyle 描述为通过。新增源码未增加这类问题。

## 复现

参见 `mfcl-native/README.md` 的命令。真实安装测试还需要：

```sh
MFCL_NATIVE_TEST_EXECUTABLE="$PWD/mfcl-native/target/release/mfcl-native" \
MFCL_LIVE_GAME_INSTALL=1 MFCL_LIVE_GAME_LAUNCH=1 \
MFCL_LIVE_GAME_JAVA=/path/to/java17/bin/java \
./gradlew :HMCLCore:test \
  --tests '*DefaultGameRepositoryDraftTest.liveVanillaInstallUsesNativeDownloads' \
  --rerun-tasks --no-daemon
```

Forge 实装在上面命令中增加 `MFCL_LIVE_FORGE_VERSION=47.2.0`。
需要网络和可用图形显示；不要把明确跳过的跨平台或实时账号测试计入已验收范围。
