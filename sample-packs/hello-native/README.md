# hello-native（native 扩展包样例）

最小 ABI-1 native 包：一个 `arm64-v8a` 组件 `libhello_native.so`，导出 `hello`、`echo` 两个命令。

- manifest：`pack.json`（`abi: "arm64-v8a"`、`abiVersion: 1`；未写 `mode`，由宿主默认加载模式决定）
- 源码：`jni/hello_native.c` 与 `jni/pack_api_v1.h`——**逐字节复制**自仓库
  `modules/pack/src/main/cpp/demo/hello_native.c` 与 `modules/pack/src/main/cpp/pack_api_v1.h`（本轮用 `cp` 复制）

**本目录未包含编译好的 `.so` 二进制**；NDK 在本机尚未核实可用，构建命令见下且本轮未执行。

## 符号协议（ABI 1，固定契约）

`.so` 只需导出**一个**符号，宿主也只 `dlsym` 它：

```c
const pack_api_v1 *pack_get_api_v1(int32_t host_abi_version,
                                   const pack_host_api_v1 *host,
                                   pack_api_v1 *out_api);
```

`pack_api_v1` 提供 `open / invoke / free_buffer / close` 函数表；每次调用收到 `pack_call_v1`（`check_active`、`now_nanos`、`deadline_nanos`、`retain_call`/`release_call`）。完整契约（含回绕安全超期比较、引用计数与 `close` 驻留语义）见 `docs/pack-api.md` §5 与头文件注释。仅 `JNI_OnLoad` 的库不是 ABI-1 包。

本 demo 的行为：

- `hello` → 返回 `{"ok":true,"runtime":"native","abiVersion":1}`
- `echo` → 原样返回参数 JSON（须非空）

## 构建 libhello_native.so

用 NDK clang 交叉编译（minSdk 23 对应 android23；minSdk 版本与宿主一致）：

```bash
NDK=<你的NDK路径>
CLANG=$NDK/toolchains/llvm/prebuilt/<host-tag>/bin/clang

$CLANG --target=aarch64-linux-android23 -shared -fPIC \
  -O2 -Wall -Wextra \
  -o libhello_native.so \
  jni/hello_native.c
```

可选：用 `readelf --dyn-syms libhello_native.so | grep pack_get_api_v1` 确认导出符号（`hello_native.c` 已用 `__attribute__((visibility("default")))` 标记）。

## 打包 ZIP

```bash
zip hello-native.zip pack.json libhello_native.so
```

## 加载模式

manifest 可选 `mode`：`"file"`（默认路径之一，从已验证只读私有文件加载）或 `"memfd"`（密封 memfd，仅 API 30+；低版本宿主显式 `NATIVE_PLATFORM` 失败，不静默回退）。本样例未指定，交给宿主默认。

## 安全边界

native 与 DEX 同为**进程内可信代码**：无法释放时 `close` 返回 `PACK_NATIVE_STILL_RESID`，宿主不 `dlclose` 并如实标记 `NEEDS_RESTART`。超时/取消是协作式检查，不是硬杀。见 `docs/pack-api.md` §5。
