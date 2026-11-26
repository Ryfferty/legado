cmake_minimum_required(VERSION 3.22.1)
project(legado_pack_runtime LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "The pack JNI bridge requires an Android NDK toolchain")
endif()

add_library(legado_pack_bridge SHARED src/main/cpp/pack_jni_bridge.cpp)
target_compile_features(legado_pack_bridge PRIVATE cxx_std_17)
target_compile_options(legado_pack_bridge PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(legado_pack_bridge PRIVATE dl)
# Both the host bridge and demo must map on 4KB and 16KB Android devices.
target_link_options(legado_pack_bridge PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=4096")

# Build explicitly for sample-packs/device tests. Do not ship this demo as a host
# runtime dependency. The integration step copies the REAL built .so per ABI.
option(PACK_BUILD_NATIVE_DEMO "Build the ABI-1 smoke-test pack library" OFF)
if(PACK_BUILD_NATIVE_DEMO)
    add_library(pack_hello_native SHARED src/main/cpp/demo/hello_native.c)
    target_include_directories(pack_hello_native PRIVATE src/main/cpp)
    target_compile_options(pack_hello_native PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
    target_compile_features(pack_hello_native PRIVATE c_std_11)
    target_link_options(pack_hello_native PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=4096")
endif()

# Optional on-device ABI executable. This tests the C contract only; JNI/FD
# loading, retained-call concurrency and linker policy need separate device tests.
option(PACK_BUILD_NATIVE_SMOKE "Build the hello-native ABI smoke executable" OFF)
if(PACK_BUILD_NATIVE_SMOKE)
    add_executable(pack_hello_native_smoke
        src/main/cpp/tests/nativebridge/hello_native_smoke.c
        src/main/cpp/demo/hello_native.c)
    target_include_directories(pack_hello_native_smoke PRIVATE src/main/cpp)
    target_compile_features(pack_hello_native_smoke PRIVATE c_std_11)
    target_compile_options(pack_hello_native_smoke PRIVATE -Wall -Wextra -Werror)
    target_link_options(pack_hello_native_smoke PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=4096")
endif()
