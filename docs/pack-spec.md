# 扩展包规范（pack-spec）

本文描述 Legado fork 扩展包的**包格式与 manifest 规范**。所有内容以 `modules/pack` 当前源码为准：

- manifest 模型与校验：`modules/pack/src/main/java/io/legado/pack/api/PackManifest.kt`
- 严格解析器：`modules/pack/src/main/java/io/legado/pack/manifest/PackManifestParser.kt`
- 标识/版本/网络：`api/PackIds.kt`、`api/PackVersion.kt`、`api/NetworkPolicy.kt`
- 存储布局：`api/PackStorageLayout.kt`

规范状态：**manifest 解析与校验逻辑已在源码中实现并有单元测试文件**（`modules/pack/src/test/...`），但本轮未执行任何构建或测试（见文末“未实现/未验证”）。

## 1. 包格式

两种可导入格式（`PackArchiveSpec`，`api/PackManifest.kt:416`）：

| 格式 | 载体 | manifest 位置 | 说明 |
| --- | --- | --- | --- |
| ZIP 包 | `.zip` 文件 | 根目录条目名必须为 `pack.json` | 可声明 JS / DEX / native 任意组合 |
| 单文件 JS 包 | 一个 JS 文本文件 | `// ==Pack==` … `// ==/Pack==` 头 | 只能是 JS；导入器把头之后的正文存为固定入口 `main.js`，不能声明 DEX/native |

限制：manifest 最大 1,048,576 字符（`MAX_MANIFEST_CHARS`）；单文件头最多 512 行、每行最多 4096 字符。

## 2. pack.json 字段

顶层字段（**未知字段一律拒绝**，`PackManifestParser.kt` `checkAllowed`）：

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `schemaVersion` | 整数 | 是 | 必须为 `1`；高于宿主支持值报 `MANIFEST_SCHEMA_UNSUPPORTED` |
| `id` | 字符串 | 是 | 2–64 字符，`[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*`，不得为 Windows 保留名（con/prn/aux/nul/com1-9/lpt1-9）（`PackIds.kt:15`） |
| `name` | 字符串 | 是 | 非空，≤128 |
| `version` | 字符串 | 是 | ≤64，`[0-9A-Za-z][0-9A-Za-z._+-]{0,63}` |
| `versionCode` | 整数 | 是 | ≥1，单调递增用于覆盖升级 |
| `packLib` | 字符串 | 是 | 版本门，见 §3 |
| `description` | 字符串 | 否 | ≤1024，可空 |
| `author` | 字符串 | 否 | ≤128，可空 |
| `capabilities` | 字符串数组 | 否 | 见 §4；重复项报错 |
| `network` | 对象 | 否 | 缺省即 `none`，见 §5 |
| `components` | 数组 | 是 | 非空，见 §6 |
| `commands` | 数组 | 是 | 非空，见 §7 |
| `mcpTools` | 数组 | 否 | 需能力 `MCP_TOOLS`，见 §8 |
| `apiRoutes` | 数组 | 否 | 需能力 `API_ROUTES`，见 §9 |
| `selfTests` | 数组 | 否 | 需能力 `SELF_TEST`，见 §10 |

JSON 语法为**严格模式**（`manifest/StrictJsonParser.kt`）：重复字段、非约定类型、浮点数当整数、未知字段都是红牌错误 `MANIFEST_INVALID`。解析是纯函数：不执行 JS、不读 ZIP、不加载类、不碰文件系统。

## 3. packLib 版本门

框架当前版本：`PackLibVersion.CURRENT = 1.0.0`（`PackVersion.kt:31`）。

`packLib` 语法（`VersionRange`）：`*` 接受一切；否则为一个或多个空格分隔的比较子 `>=1.0.0`、`>1.0.0`、`<=2.0.0`、`<2.0.0`、`=1.2.3`（全部 AND）。不支持 `^`、`~`、`||`。版本号必须是 `major.minor.patch` 三段十进制。不满足时导入报 `PACKLIB_INCOMPATIBLE`。

推荐写法：`">=1.0.0 <2.0.0"`。

## 4. capabilities

可选值（`PackCapability`，精确大写）：`KV`、`HTTP`、`CRYPTO`、`LOG`、`MCP_TOOLS`、`API_ROUTES`、`SOURCE_COMMANDS`、`SELF_TEST`、`SCHEDULER`。

能力只是**声明**：不声明对应能力却使用对应功能（mcpTools/apiRoutes/selfTests/source 导出命令）在 manifest 校验阶段即被拒绝。能力与运行时配额由宿主执行层落实。

## 5. network

`network` 对象字段：`mode`（必填）、`patterns`、`reason`。

| mode | 字段 | 语义 |
| --- | --- | --- |
| `none` | 无（出现 patterns/reason 报错） | 一切网络禁用（缺省行为） |
| `patterns` | `patterns`: 非空字符串数组 | 域名白名单；`reason` 不允许 |
| `open` | 可选 `reason` ≤512 | 任意主机；导入 UI 必须显式确认，manifest 解析会附带 `NETWORK_OPEN_REQUIRES_CONSENT` 警告 |

域名模式（`NetworkPolicy.kt`）：精确主机名匹配一个 host；`*.example.com` 仅匹配子域（不匹配 `example.com` 本身）；通配符后必须至少两个标签（拒绝 `*.com`）；不接受端口、scheme、路径与 IPv6 字面量。**重定向逐跳重新校验**。

## 6. components

每个组件字段按 `type` 区分（未知字段拒绝）：

**JS**（`type: "js"`）：`id`、`type`、`entry`（相对路径，指向包内 JS 文件）。

**DEX**（`type: "dex"`）：`id`、`type`、`path`（相对路径，指向包内 `.dex` 文件）、`class`（入口类全名，≤512，二进制类名格式）。

**native**（`type: "native"`）：`id`、`type`、`path`（相对路径，指向 `.so`）、`abi`（`armeabi-v7a` | `arm64-v8a` | `x86` | `x86_64`）、可选 `mode`（`file` | `memfd`，memfd 仅 API 30+，请求于低版本宿主是显式 `NATIVE_PLATFORM` 失败，绝不静默回退 file）、可选 `abiVersion`（整数 ≥1，缺省 1；当前唯一 ABI 版本为 **1**）。

组件 `id`：`[a-z][a-z0-9_-]{0,63}`，不得以 `_`/`-` 结尾，包内唯一。相对路径（`RelativePath`）：`/` 分隔，每段 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`、不以 `.` 或空格结尾，总长 ≤512，拒绝绝对路径、反斜杠、空段、`.`/`..`、盘符与控制字符。

声明组件 ≠ 可执行：宿主 runtime provider 报告可用性并在**首次调用时懒加载**。

## 7. commands

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `name` | 是 | `[a-z][a-z0-9_-]{0,63}`，不以 `_`/`-` 结尾，包内唯一 |
| `component` | 是 | 必须引用已声明的组件 id |
| `description` | 否 | ≤512 |
| `source` | 否 | 布尔，默认 false；true 表示普通书源 JS 可经窄桥调用，需能力 `SOURCE_COMMANDS` |

命令与具体运行时的入口映射：JS → `__packExports[command]`；DEX → `PackEntry.invoke(command,…)`；native → `invoke(handle,command,…)`。见 `docs/pack-api.md`。

## 8. mcpTools

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `name` | 是 | `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`，不以 `_`/`-` 结尾，包内唯一 |
| `command` | 是 | 必须引用已声明命令 |
| `description` | 否 | ≤1024 |
| `inputSchema` | 否 | JSON Schema 受限子集（`api/PackToolSchema.kt` 校验），缺省空 object schema |
| `write` | 否 | 布尔，默认 false；写工具宿主默认拒绝执行，须显式授权 |

工具名在宿主 MCP Server 上以**裸名**注册，不得与宿主保留名冲突（`TOOL_CONFLICT`，见 `docs/pack-mcp.md`）。

## 9. apiRoutes

声明式 Web 路由，路径为 `/packs/<packId>/api/<name>`（`RouteSpec`，`api/PackManifest.kt:193`）。

字段：`name`（同命令名格式）、`method`（`GET` | `POST`）、`command`（引用命令）、`write`（布尔；`write:true` 必须 `POST`）。

## 10. selfTests

字段：`name`、`command`（引用命令）、可选 `timeoutMs`（1–60000）。自检只在显式 `selfTest` 调用时执行；报告绑定 runId 可单独查询。

## 11. 单文件 JS 包头

头键（`// @key value`，未知键拒绝）：`id`、`name`、`version`、`versionCode`、`packLib`、`description`、`author`、`capability`（可重复）、`network`（`none` | `patterns:<逗号列表>` | `open` | `open:<reason>`）、`command`（可重复：`<name> [source]`，自动绑定组件 `main`）、`mcpTool`（可重复：`<name> <command> [write|read] [schema-json]`）、`apiRoute`（可重复：`<name> <GET|POST> <command> [write]`）、`selfTest`（可重复：`<name> <command> [timeoutMs]`）。

头之后必须是非空 JS 正文（导入器存为 `main.js`）。

## 12. 导入语义与存储

- 导入/启用**只登记元数据**；`prepareImport` 只读取/暂存/校验，不编译 JS、不加载 DEX、不 `dlopen`；首次 `invoke`/`selfTest` 才初始化组件（`api/PackLifecycle.kt` `PackManager` 契约）。
- 权限确认绑定 manifest 派生的 `PackPermissionSet.digest()`（SHA-256）；`network=open` 必须显式确认；工具/路由授权必须在 `PackGrant.toolPermissions/routePermissions` 中显式 `true`，声明本身不等于授权（`api/PackPermissions.kt`）。
- 落盘布局（`api/PackStorageLayout.kt`）：宿主提供 packsRoot（正常为 `File(context.filesDir, "packs")`），
  ```
  <packsRoot>/<packId>/
    current.json        # 当前 revision 元数据（原子写）
    .staging/<token>/   # 导入暂存
    code/<revision>/    # 只读代码目录，revision 为内容 SHA-256
    data/  kv/  logs/  cookies/  opt/
  ```
- 每包文件、KV、cookie、日志、HTTP 客户端互相隔离；卸载撤销注册并输出残留报告（`UninstallReport`：`residualPaths`、`loadedCodeStillResident`、`needsRestart`、`clean`）。

## 13. 最佳实践（建议，非强制）

- `versionCode` 从 1 起严格递增；升级包不要复用旧 revision 内容。
- 网络优先 `patterns` 而非 `open`；`open` 必须写清 `reason`。
- 未知能力不要预先声明；只声明实际使用的原语。
- 写工具保持幂等并在 `description` 说明副作用。
- 自检覆盖每个导出命令的最小输入/输出。

## 14. 未实现 / 未验证

- 本轮**未执行**任何 Gradle 构建、单元测试、Lint 或设备验收；`modules/pack` 与 app 的接线（`settings.gradle:70` include、`app/build.gradle:307` 依赖）仅做了源码 grep 核实，未编译验证。
- `modules/pack` 的 Rhino/OkHttp/JNI/NDK 构建依赖按交接文档仍未统一核对；样例中的 DEX 与 native 二进制未编译。
- 网络配额、worker 数、日志 100MB 落盘上限等执行层机制的实现细节在 `manager/`、`primitives/`、`diagnostics/`，本文未逐一复核，规范只固化 manifest 层契约。
