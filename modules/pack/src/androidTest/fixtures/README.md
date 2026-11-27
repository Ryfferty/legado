# Native & DEX device-test fixtures

`../assets/runtime-fixtures/` holds the checked-in binary fixtures used by
`NativeRuntimeDeviceTest` and `DexRuntimeDeviceTest`:

| 资产 | 来源 | 用途 |
| --- | --- | --- |
| `hello-native-<abi>.so` | `src/main/cpp/demo/hello_native.c`（真实 ABI-1 demo 包） | FILE/MEMFD 加载、invoke、有界等待后的 released close |
| `broken-native-<abi>.so` | `src/androidTest/fixtures/broken_native.c`（无 `pack_get_api_v1` 导出） | 按路径失败闩：重试必须复用既有失败条目，不得再映射；manager 级 `NativeResidentUninstallDeviceTest` 用它验证映射后失败 → 卸载报告驻留/需重启 |
| `hello-dex.dex` | `src/androidTest/java/io/legado/pack/runtime/dex/fixture/*.java.in`（两个类：实现 PackEntry 的 HelloDexEntry 与不实现且 `<clinit>` 抛错的 NotPackEntry） | 只读加载、invoke、驻留 close；非 PackEntry 类在静态初始化前被拒 |

native ABI 覆盖 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`（测试按
`Build.SUPPORTED_ABIS.first()` 选取资产）。二进制按构建手册重新生成，不要手改。

## 重新生成 DEX 资产

模板是 `.java.in`，**不得**编进宿主/测试 APK 的 classpath，产物只作为
androidTest 资产存在（`DexRuntimeDeviceTest` 经 instrumentation context 的
assets 读取）。在本机工具链上重建（JDK 与 build-tools 版本以
`tools/android-tools` 实际为准）：

```bash
JAVAC=/c/Users/ryz/Desktop/legado/tools/android-tools/jdk/jdk-21.0.13.1+2/bin/javac.exe
D8=/c/Users/ryz/Desktop/legado/tools/android-tools/sdk/build-tools/36.1.0/d8.bat
PLATFORM=/c/Users/ryz/Desktop/legado/tools/android-tools/sdk/platforms/android-36/android.jar
API_CLASSES=legado/modules/pack/build/tmp/kotlin-classes/debug   # 供 javac/d8 解析 PackEntry/CloseOutcome
OUT=$(mktemp -d) && mkdir -p "$OUT/src/io/legado/pack/fixture" "$OUT/classes"
cd legado/modules/pack/src/androidTest/java/io/legado/pack/runtime/dex/fixture
cp HelloDexEntry.java.in "$OUT/src/io/legado/pack/fixture/HelloDexEntry.java"
cp NotPackEntry.java.in "$OUT/src/io/legado/pack/fixture/NotPackEntry.java"
cd -
"$JAVAC" -cp "$API_CLASSES" -d "$OUT/classes" \
  "$OUT/src/io/legado/pack/fixture/HelloDexEntry.java" "$OUT/src/io/legado/pack/fixture/NotPackEntry.java"
"$D8" --min-api 23 --lib "$PLATFORM" --classpath "$API_CLASSES" --output "$OUT" \
  "$OUT"/classes/io/legado/pack/fixture/*.class
cp "$OUT/classes.dex" legado/modules/pack/src/androidTest/assets/runtime-fixtures/hello-dex.dex
```

生成后自查：`dexdump hello-dex.dex` 应列出 `HelloDexEntry`（实现
`io/legado/pack/api/PackEntry`）与 `NotPackEntry`（无接口、含 `<clinit>`），
DEX 版本 `035`（min-api 23）。

## 重新生成 native 资产

用本仓库的 NDK（`tools/android-tools/sdk/ndk/<版本>`，版本以
`gradle/libs.versions.toml` 的 `ndk` 为准）：

```bash
NDK=/c/Users/ryz/Desktop/legado/tools/android-tools/sdk/ndk/28.2.13676358
CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
cd legado/modules/pack

for spec in arm64-v8a:aarch64-linux-android23 \
            armeabi-v7a:armv7a-linux-androideabi23 \
            x86:i686-linux-android23 \
            x86_64:x86_64-linux-android23; do
  abi="${spec%%:*}"; triple="${spec##*:}"
  "$CLANG" --target="$triple" -shared -O2 -std=c11 -Wall -Wextra -Werror \
    -fvisibility=hidden -I src/main/cpp src/main/cpp/demo/hello_native.c \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096 -Wl,-s \
    -o "src/androidTest/assets/runtime-fixtures/hello-native-$abi.so"
  "$CLANG" --target="$triple" -shared -O2 -std=c11 -Wall -Wextra -Werror \
    -fvisibility=hidden src/androidTest/fixtures/broken_native.c \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096 -Wl,-s \
    -o "src/androidTest/assets/runtime-fixtures/broken-native-$abi.so"
done
```

链接标志与 `CMakeLists.txt` 中 `pack_hello_native` demo 目标一致
（4KB/16KB 页设备均可映射）。生成后可用
`llvm-nm -D`/`llvm-readelf -h` 自查：hello 导出 `pack_get_api_v1`，broken 无导出，
两者均为 `DYN` 且 machine 与 ABI 匹配。
