# 扩展包样例（sample-packs）

四类可导入样例，对应规范 `docs/pack-spec.md`、运行时 API `docs/pack-api.md`、MCP 协议 `docs/pack-mcp.md`。

| 目录 | 类型 | 形态 | 可直接导入？ |
| --- | --- | --- | --- |
| `hello-js/` | JS | 单文件包 `hello-js.pack.js`（`// ==Pack==` 头） | 是（纯文本，无需构建） |
| `mock-mcp-demo-add/` | JS + MCP 工具 | 单文件包 `mock-mcp-demo-add.pack.js`，注册 `demo_add` | 是（纯文本，无需构建） |
| `hello-dex/` | DEX | `pack.json` + 入口 Java 源码 | 需先按其 README 构建 `classes.dex` 并打 ZIP |
| `hello-native/` | native | `pack.json` + `jni/` 下 C 源与 ABI 头 | 需先按其 README 用 NDK 编译 `.so` 并打 ZIP |

共同性质（规范要求，源码已实现解析）：

- 导入/启用**只登记元数据**，首次调用才加载执行；
- `hello-js` 声明 `SELF_TEST` 能力并带 `@selfTest smoke hello`，导入后可用自检验证；
- `mock-mcp-demo-add` 声明 `MCP_TOOLS` 能力，启用后 `demo_add` 以裸名出现在 MCP `tools/list`（需宿主构建后实测，本轮未运行）；
- 两个待构建样例的命令应答均为最小 JSON（`hello`/`echo`）。

**状态声明**：DEX 的 `classes.dex` 与 native 的 `.so` 均未编译（NDK 可用性未核实）；四例均未在本轮做导入实测——宿主整体构建/测试尚未执行（依据交接文档与本轮核实）。文件清单与依据见各目录 README。

## 快速试用（构建宿主后）

```bash
cd legado && ./gradlew :app:assembleAppDebug   # 尚未在本环境成功执行
```

1. 在应用「扩展包」管理界面导入 `hello-js.pack.js`（或 `mock-mcp-demo-add.pack.js`）；
2. 启用包；对 `hello-js` 可运行自检；
3. 对 `mock-mcp-demo-add`：MCP 客户端连 `http://<host>:1236/mcp`（头 `X-Legado-Token`），`tools/list` 应含 `demo_add`，调用 `{"a":2,"b":3}` 应得到 `{"code":"OK","data":{"sum":5,"a":2,"b":3},...}`。
