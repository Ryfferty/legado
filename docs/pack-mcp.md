# 扩展包 MCP 协议（pack-mcp）

本文描述扩展包在宿主 MCP 服务上的对外协议。依据源码：

- 宿主管理工具：`app/src/main/java/io/legado/app/web/mcp/McpPackTools.kt`
- 包工具投影：`app/src/main/java/io/legado/app/web/mcp/PackMcpBridge.kt`
- 响应封套：`app/src/main/java/io/legado/app/web/mcp/PackMcpJson.kt`、`app/src/main/java/io/legado/app/help/pack/PackJson.kt`
- 保留名：`app/src/main/java/io/legado/app/help/pack/HostMcpToolNames.kt`

## 1. 端点与鉴权

- MCP 默认端口 **1236**、路径 `/mcp`、令牌头 `X-Legado-Token`（`AppConfig.kt:572` mcpPort 缺省 1236、`McpService.kt:226`、`McpAccess.kt:8` `TOKEN_HEADER`；路径 `/mcp` 依据工作区 AGENTS.md）。
- 所有工具（宿主管理工具与包工具）一律要求令牌；**包工具不豁免**（`McpPackTools.kt:27` 注释明确）。
- `docs` 中的 1234 端口为历史示例，不是本 fork 默认值。

## 2. 响应封套

每个工具结果同时以文本与 `structuredContent` 携带同一对象（`PackMcpJson.fromPayload`）：

```json
{ "code": "OK", "data": { ... }, "message": null, "isError": false }
```

- 成功：`code = "OK"`、`isError = false`（`PackJson.kt:41`）。
- 失败：`code` 为 `PackErrorCode` wire 值（如 `PACK_NOT_FOUND`、`INVALID_REQUEST`）或 `REPORT_NOT_FOUND`，`isError = true`；诊断失败时 `data` 为完整 `PackDiagnostic` 对象（code/message/path/details）。
- 超时/取消映射为 `TIMEOUT`/`CANCELLED`，不会伪装成成功。

## 3. 宿主管理工具（`McpPackTools.register`）

这些工具属于宿主，不受包授权管理，但同样需要令牌：

| 工具 | 入参 | 语义 |
| --- | --- | --- |
| `pack_list` | 无 | 列出已安装包、启用状态、声明工具/路由/自检、写授权状态、注册告警 |
| `pack_info` | `id`（必填） | 单包 manifest 元数据；**不执行包代码** |
| `pack_selftest` | `id`（必填） | 执行声明的自检，返回绑定 runId 的完整报告 |
| `pack_selftest_report` | `id` 必填、`runId` 可选 | 只读查询既有报告；runId 省略取最新；不存在时报 `REPORT_NOT_FOUND` |
| `pack_tool_permissions` | `id`（必填） | 列出声明工具与当前写入授权状态 |
| `pack_set_write_tool` | `id`、`tool`、`enabled`（均必填） | 显式启停写工具；仅 manifest 标记 `write` 的工具可授权 |
| `pack_set_enabled` | `id`、`enabled`（必填） | 启停包；禁用立即撤销 MCP 工具注册，已登记回调带 epoch 校验 |
| `pack_uninstall` | `id`（必填） | 卸载并撤销注册，返回残留文件与 `NEEDS_RESTART` 状态；有残留时返回 `RESIDUAL_FILES` 错误 |

## 4. 包声明工具的注册（`PackMcpBridge.kt`）

- 包 manifest 的 `mcpTools` 由 bridge 投影到**全局共享**的一个 MCP Server，注册名就是 manifest 里的**裸工具名**（如 `demo_add`），不加前缀（`PackMcpBridge.kt:150` `desired[spec.name]`、`installLocked` `Tool(name = name…)`）。
- **保留名**（大小写不敏感）不得使用，manifest 校验即报 `TOOL_CONFLICT`（`HostMcpToolNames.RESERVED`）：
  - BUILTIN：`save_source`、`debug_source`、`list_sources`、`get_source`、`delete_sources`、`get_http_logs`、`get_http_log`、`set_http_log_recording`、`get_cookies`、`set_cookie`、`clear_cookies`、`eval_js`、`check_source`
  - PACK_MANAGEMENT：上表 8 个 `pack_*` 工具
- 同名冲突在注册表阶段报 `REGISTRATION_CONFLICT`/`TOOL_CONFLICT`；注册失败的包会得到告警（可通过 `pack_list`/`pack_info` 的 `mcpWarnings` 读取），不静默丢弃。
- 注册的 `Tool` 带注解：读工具 `readOnlyHint=true`；写工具 `destructiveHint=true`；并带元数据：
  ```json
  {
    "io.legado.pack/id": "<packId>",
    "io.legado.pack/revision": "<64位hex>",
    "io.legado.pack/epoch": 1,
    "io.legado.pack/tool": "demo_add",
    "io.legado.pack/declaredWrite": false
  }
  ```
- 服务器开启 `tools.listChanged`：启停/卸载/升级导致工具增删时向所有在线 session 推送 `notifications/tools/list_changed`，客户端无需自行轮询。
- 每个工具 handler 捕获注册时的 `PackRegistration`；调用经 `PackInvocation` 以 `expectedRef`+epoch 原子入队，禁用/升级后的旧闭包不会派发到新 revision。
- **写工具默认关闭**：`write:true` 的工具须先 `pack_set_write_tool` 授权，否则调用返回 `PERMISSION_DENIED`。
- 禁用/卸载同步撤销宿主写授权与 MCP 注册。

## 5. 端到端示例：`demo_add`

样例包 `sample-packs/mock-mcp-demo-add/mock-mcp-demo-add.pack.js`（单文件 JS 包，声明 `MCP_TOOLS` 能力、`add` 命令、`demo_add` 工具与 inputSchema）。

导入并启用后 `tools/list` 中出现：

```json
{
  "name": "demo_add",
  "description": "（扩展包 mock-mcp-demo-add）",
  "inputSchema": {
    "type": "object",
    "properties": { "a": { "type": "number" }, "b": { "type": "number" } },
    "required": ["a", "b"]
  }
}
```

调用 `tools/call`：

```json
{ "name": "demo_add", "arguments": { "a": 2, "b": 3 } }
```

返回（命令返回值 `{sum,a,b}` 进入封套 `data`）：

```json
{
  "code": "OK",
  "data": { "sum": 5, "a": 2, "b": 3 },
  "message": null,
  "isError": false
}
```

失败示例（包未导入）：

```json
{ "code": "PACK_NOT_FOUND", "data": { "code": "PACK_NOT_FOUND", "message": "扩展包 mock-mcp-demo-add 未安装" }, "isError": true }
```

## 6. 授权与安全边界

- 声明 MCP 工具需能力 `MCP_TOOLS`；`inputSchema` 必须在 `PackToolSchema` 支持的 JSON Schema 子集内，超集在导入即被拒（`UNSUPPORTED`/`MANIFEST_INVALID`）。
- 工具调用走与命令相同的懒加载、超时与取消；MCP 请求取消映射为协作取消。
- JS 命令运行在 Rhino 沙箱（详见 `docs/pack-api.md` §2）；DEX/native 命令是进程内可信代码，协议上不宣称同等沙箱隔离。
- 工具级写授权、`network=open` 导入确认、权限摘要绑定 revision/hash 的确认流在宿主 UI 与 `PackGrant` 中落实。

## 7. 未实现 / 未验证

- 本轮**未运行** MCP 服务器或任何 tools/list / tools/call 实测；第 5 节示例中 tools/list 条目为按 `PackMcpBridge.installLocked` 的构造逻辑推导的形态（描述文案会被 bridge 附加包名后缀），封套结构直接来自 `PackJson`/`PackMcpJson` 源码。
- 依赖 app 与 `modules:pack` 的编译接线（`settings.gradle:70`、`app/build.gradle:307` 已 grep 核实存在），但整体构建未执行，MCP 依赖的 Kotlin SDK 链路未实机验证。
- `PackMcpSchema.toToolSchema` 对受限子集到 MCP SDK `ToolSchema` 的转换细节（`app/src/main/java/io/legado/app/help/pack/PackMcpSchema.kt`）本轮未逐行复核。
