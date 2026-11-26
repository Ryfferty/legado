# hello-dex（DEX 扩展包样例）

最小 DEX 包：一个组件（`classes.dex`）、入口类 `io.legado.pack.sample.HelloDexEntry`，导出两个命令 `hello`、`echo`。

- manifest：`pack.json`（ZIP 包格式，规范见 `docs/pack-spec.md`）
- 入口源码：`src/io/legado/pack/sample/HelloDexEntry.java`（实现 `io.legado.pack.api.PackEntry`；与仓库 androidTest fixture `modules/pack/src/androidTest/.../HelloDexEntry.java.in` 同构）

**本目录未包含编译好的 `classes.dex`**——构建命令见下，本轮未执行。

## 构建 classes.dex

入口类编译时需要 `modules:pack` 的 `PackEntry`、`PackCallContext`、`PackContext`、`CloseOutcome` 类。步骤（在仓库根 `legado/` 下，Git Bash）：

```bash
# 1. 构建 pack 模块，得到包含 Kotlin 类的 AAR
./gradlew :modules:pack:assembleRelease

# 2. 解出 AAR 里的 classes.jar 作为 javac classpath
mkdir -p build/sample && cd build/sample
unzip -o ../../modules/pack/build/outputs/aar/pack-release.aar classes.jar -d .

# 3. 编译入口源码（--release 8 与 minSdk 23 的 dex 兼容；路径按实际输出调整）
javac --release 8 -cp classes.jar \
  -d classes \
  ../../sample-packs/hello-dex/src/io/legado/pack/sample/HelloDexEntry.java

# 4. D8 转 dex（使用 SDK build-tools 的 d8；min-api 23）
"$ANDROID_HOME/build-tools/36.1.0/d8" --min-api 23 \
  --output ../../sample-packs/hello-dex \
  classes/io/legado/pack/sample/HelloDexEntry*.class
```

> 上述命令**未在本轮执行**；AAR 名称/输出路径以实际 Gradle 输出为准。`CloseOutcome` 是 Kotlin 类，`released()` 经由其 companion 调用（`CloseOutcome.Companion.released()`），javac 可直接编译。

## 打包 ZIP

`pack.json` 必须是 ZIP 根条目（`PackArchiveSpec.ZIP_MANIFEST_ENTRY`）：

```bash
cd sample-packs/hello-dex
zip hello-dex.zip pack.json classes.dex
```

## 预期行为

导入（导入 UI 或宿主流程）→ 启用（只登记元数据）→ 首次调用 `hello` 才加载 DEX 并初始化：

- `hello` → `{"ok":true,"runtime":"dex"}`
- `echo` → 原样返回调用参数 JSON

命令应答由 `McpPackTools`/Web 路由按 `docs/pack-api.md` 的封套返回。

## 安全边界

DEX 是**进程内可信代码**：与 JS 沙箱不同，不具备同等隔离；无法释放时宿主会如实标记 `NEEDS_RESTART`。见 `docs/pack-api.md` §4、§8。
