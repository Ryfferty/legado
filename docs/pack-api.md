# 扩展包运行时 API（pack-api）

本文描述包作者可用的**运行时调用面**。字段与签名以源码为准：

- JS 协议：`modules/pack/src/main/java/io/legado/pack/api/PackJsProtocol.kt`
- HTTP 消息：`api/PackJsHttpProtocol.kt`
- DEX/native 入口：`api/PackRuntime.kt`、`api/PackLifecycle.kt`、androidTest fixture `HelloDexEntry.java.in`
- native ABI：`modules/pack/src/main/cpp/pack_api_v1.h`
- 错误码：`api/PackErrors.kt`

## 1. 调用模型（所有运行时相同）

- **懒加载**：导入/启用只登记元数据。首次 `invoke`/`selfTest` 触发组件加载、顶层初始化和首个调用，三者在**同一个原始 deadline/cancel** 下执行，不会重新计时（`PackLifecycle.kt` `PackManager` 契约；交接复核结论）。
- 调用入口 `PackManager.invoke(...)`/`invokeBlocking(...)`：普通 overload 只允许 `PackCaller.Source`/`Host`（书源 JS 与宿主）；MCP/Web 注册回调必须走 `PackInvocation`（带 `expectedRef`/入口身份的原子入队），旧闭包不得绕过。
- 失败返回类型化 `PackDiagnostic`/`PackException`，**禁止静默降级**；红牌错误必须向用户展示。

## 2. JS 组件协议

入口脚本在包 worker 上执行一次，必须定义（`PackJsProtocol.kt`）：

```js
globalThis.__packExports = {
    hello: function (arg) { return { ok: true }; }
};
```

- 命令名与 `__packExports` 属性一一对应：`invoke(command, argJson, call)` 精确解析 `__packExports[command]`。
- `argJson` 解析为 JSON 标量/数组/对象后作为**唯一参数**传入；空字符串表示 `null`。
- 返回值序列化为 JSON；`undefined` 变为 `null`。函数、循环引用、Java 对象、宿主流、超过 1,048,576 字符（`MAX_JSON_CHARS`）或 64 层深度（`MAX_VALUE_DEPTH`）的值被拒绝，不会静默转换。

宿主注入的唯一桥是普通 JS 对象 `globalThis.__packApi`（`API_OBJECT`），对应 `PackJsPrimitiveFacade`。跨界值只允许字符串/数字/布尔/null/数组/纯对象，二进制为 Base64 文本：

| 函数 | 签名 | 说明 |
| --- | --- | --- |
| `kvGet` | `(key: string) → string?` | 每包隔离 KV，需能力 `KV`（能力声明层面） |
| `kvPut` | `(key: string, value: string) → void` | |
| `kvRemove` | `(key: string) → void` | |
| `log` | `(level: string, message: string) → void` | 级别 `debug/info/warn/error`（与 native 侧 0–3 对应） |
| `http` | `(requestJson: string) → responseJson` | 见 §3；需网络声明 |
| `cryptoDigest` | `(algorithm, dataBase64) → string` | 摘要，Base64 出 |
| `cryptoHmac` | `(algorithm, keyBase64, dataBase64) → string` | HMAC |
| `cryptoCipher` | `(spec, keyBase64, ivBase64?, dataBase64) → string` | 加解密 |

另有 `invokeComponent(componentId, command, argJson, call)` 供包内组件互调（由运行时提供 call，不由脚本传参）。

**沙箱边界**：JS 在独立 Rhino ContextFactory 中运行，Java 类访问 deny-all；不向 JS 暴露 `PackContext`、Android Context、File、流、ClassLoader 或任何 Java 对象/代理。不放宽既有书源 `RhinoClassShutter`。

## 3. HTTP 消息（`PackJsHttpProtocol.kt`）

请求对象（JS 纯对象 → JSON 字符串）：

```json
{
  "method": "POST",
  "url": "https://example.com/x",
  "headers": {"X-Test": ["a","b"]},
  "bodyBase64": "AQID",
  "followRedirects": true,
  "maxResponseBytes": 65536
}
```

- `method`：必填，大写 HTTP token；`url`：必填，绝对 http/https、有 host、禁 userinfo/fragment。
- 请求头禁 `Host`、`Content-Length`、`Transfer-Encoding`（宿主控制）；头名/头值有格式与长度上限。
- `bodyBase64` 为 RFC 4648 标准 Base64；省略/null 表示无 body。
- 响应对象：`statusCode`(100–599)、`headers`、`finalUrl`、`contentLength`（可 null）、`bodyBase64`（可为空串）。
- **非 2xx 仍是正常响应对象**；传输/策略/尺寸/取消失败抛类型化 `PackException`，绝不伪造 HTTP 状态。
- 上限：HTTP JSON ≤24,000,000 字符；请求体 ≤1 MiB；响应体 ≤16 MiB；`maxResponseBytes` 不得超宿主上限。重定向逐跳按网络策略校验。

## 4. DEX 组件入口

入口类必须实现 `io.legado.pack.api.PackEntry`（`api/PackRuntime.kt`；样例见 androidTest fixture 与 `sample-packs/hello-dex/`）：

```java
public interface PackEntry {
    void initialize(PackContext context, PackCallContext call);
    String invoke(String command, String argJson, PackCallContext call);
    CloseOutcome close();
}
```

- `invoke` 返回 UTF-8 JSON 字符串；命令不在 manifest 内由框架拒绝。
- 初始化与首个调用共用原始 deadline/cancel；长操作应周期性 `call.checkActive(...)` 协作检查。
- `close()` 返回 `CloseOutcome`（fixture 使用 `CloseOutcome.Companion.released()`）；无法释放时如实报告，宿主转 `NEEDS_RESTART`。
- **DEX 是进程内可信代码**：与 JS 沙箱不同，不能宣称同等隔离；卡死只能隔离并诚实报告，线程中断映射为协作取消而非硬杀。

## 5. Native ABI 1（`src/main/cpp/pack_api_v1.h`）

`.so` 必须导出**唯一**符号（host 只 `dlsym` 这一个）：

```c
const pack_api_v1 *pack_get_api_v1(int32_t host_abi_version,
                                   const pack_host_api_v1 *host,
                                   pack_api_v1 *out_api);
```

`pack_api_v1` 函数表：`open(verified_private_path, mode, abi_version, call) → handle`、`invoke(handle, command, arg_json, arg_len, call, &out_json, &out_len) → int32`、`free_buffer(handle, buf, len)`、`close(handle) → int32`。

- `mode`：`0`=file（已验证只读私有路径）、`1`=memfd（密封 memfd，仅 API 30+）。
- `open` 收到与首个 invoke 相同的首调用上下文；其内初始化必须遵守同一 deadline/cancel，可 `retain_call` 后启动 worker。
- 每次 invoke 收到宿主拥有的 `pack_call_v1`：`check_active`（线程安全，返回 ACTIVE/CANCELLED/TIMEOUT）、`now_nanos`（System.nanoTime 同源）、`deadline_nanos`、`retain_call`/`release_call`（引用计数）。超期判定**必须**用回绕安全比较：
  ```c
  (int64_t)((uint64_t)now_nanos - (uint64_t)deadline_nanos) >= 0
  ```
  不得改用墙钟。跨线程或 invoke 返回后持有 call 前必须 `retain_call`，用毕恰好 `release_call` 一次。
- `invoke` 写入新分配的 UTF-8 JSON 缓冲；宿主先复制再调 `free_buffer`。负数状态时必须保持 `*out_json == NULL`、`*out_len == 0`。不得跨越 C 边界抛 C++ 异常。
- `close` 仅在无任何 call/线程/引用时返回 `PACK_NATIVE_RELEASED(0)`；否则 `PACK_NATIVE_STILL_RESIDENT(1)`，宿主不得 `dlclose`。成功 close 后不得再调任何 ABI 符号。
- 状态/错误码：`OPEN_OK=0`；`ERR_INVALID_ARGUMENT=-1`、`ERR_LOAD_FAILED=-2`、`ERR_CANCELLED=-3`、`ERR_TIMEOUT=-4`；call 状态 `ACTIVE=0/CANCELLED=1/TIMEOUT=2`。
- 宿主侧 `pack_host_api_v1` 提供 `log(host, level 0–3, message UTF-8 非NUL结尾, length)`。
- 仅 `JNI_OnLoad` 的库**不是** ABI-1 包。
- native 同为进程内可信代码，manifest 的 `abiVersion` 当前固定为 1。

## 6. 错误码（`PackErrors.kt`）

稳定 wire 值（新增允许，既有值不变）。红牌（RED_CARD，必须展示）：`MANIFEST_INVALID`、`MANIFEST_SCHEMA_UNSUPPORTED`、`PACKLIB_INCOMPATIBLE`、`COMPONENT_MISSING`、`PERMISSION_DENIED`、`TOOL_CONFLICT`、`ROUTE_CONFLICT`、`REGISTRATION_CONFLICT`、`NATIVE_PLATFORM`、`LOAD_FAILED`、`TIMEOUT`、`NEEDS_RESTART`、`RESIDUAL_FILES`、`CRYPTO_UNSUPPORTED`、`PACK_NOT_FOUND`、`PACK_DISABLED`、`COMMAND_NOT_FOUND`、`IMPORT_LIMIT_EXCEEDED`、`IO_FAILED`、`SELFTEST_FAILED`、`UNSUPPORTED`、`INTERNAL`。WARNING：`INVALID_REQUEST`、`NETWORK_OPEN_REQUIRES_CONSENT`。INFO：`CANCELLED`。

`TIMEOUT` 语义：框架停止调度新工作并返回失败；被阻塞的线程可能仍在运行（配合 `NEEDS_RESTART`）。取消是协作式（`PackCancelledException`），不是硬杀。

## 7. 最佳实践（建议，非强制）

- JS 命令保持纯函数式、返回小 JSON；大输出分页。
- 长任务在 JS/native 中周期检查取消状态；native 用引用计数管理跨线程 call。
- DEX/native 包提供 `SELF_TEST` 自检覆盖最小路径，让用户导入后可一键验证。
- 涉及账号或写操作的工具：建议在命令内实现频控与幂等键，并在工具 `description` 中写明副作用——这些是**写法建议**，框架不强制。
- 网络请求显式设置 `maxResponseBytes` 防止意外大响应。

## 8. 未实现 / 未验证

- 本轮未运行任何构建/测试；上述接口来自源码阅读（文件见文首），未经编译或实机验证。
- JS 沙箱的 Rhino 实装细节（`runtime/js/JsPackRuntimeProvider.kt`、`ContextPrimitives.kt`）与 DEX/native provider（`runtime/dex/`、`runtime/nativebridge/`）本轮只核对了契约层，未逐行复核。
- `CRYPTO_UNSUPPORTED` 覆盖的算法集合、KV 键格式限制在 `primitives/PackCryptoImpl.kt`、`PackKeyValueStoreImpl.kt`，本文未展开；包作者勿假设超出本文列出的算法可用。
