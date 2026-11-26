/* ABI smoke only: this does not validate JNI, Android FD loading or dlclose. */
#include "pack_api_v1.h"
#include <stdio.h>
#include <string.h>

#define REQUIRE(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); \
        return 1; \
    } \
} while (0)

static int32_t check_active(void *opaque) {
    return *(const int32_t *)opaque;
}

int main(void) {
    pack_host_api_v1 host = {0};
    pack_api_v1 table = {0};
    host.struct_size = sizeof(host);
    REQUIRE(pack_get_api_v1(PACK_NATIVE_ABI_VERSION + 1, &host, &table) == NULL);
    REQUIRE(pack_get_api_v1(PACK_NATIVE_ABI_VERSION, NULL, &table) == NULL);
    REQUIRE(pack_get_api_v1(PACK_NATIVE_ABI_VERSION, &host, NULL) == NULL);
    host.struct_size = 0;
    REQUIRE(pack_get_api_v1(PACK_NATIVE_ABI_VERSION, &host, &table) == NULL);
    host.struct_size = sizeof(host);
    const pack_api_v1 *api = pack_get_api_v1(PACK_NATIVE_ABI_VERSION, &host, &table);
    REQUIRE(api == &table && api->struct_size == sizeof(table));
    REQUIRE(api->open && api->invoke && api->free_buffer && api->close);

    int32_t status = PACK_CALL_ACTIVE;
    pack_call_v1 call = {0};
    call.struct_size = sizeof(call);
    call.call_handle = &status;
    call.check_active = check_active;
    const uint8_t json[] = "{\"echo\":[1,true,null]}";
    const char greeting[] = "{\"ok\":true,\"runtime\":\"native\",\"abiVersion\":1}";
    for (int32_t mode = PACK_NATIVE_MODE_FILE; mode <= PACK_NATIVE_MODE_MEMFD; ++mode) {
        REQUIRE(api->open("verified.so", mode, PACK_NATIVE_ABI_VERSION + 1, &call) == NULL);
        void *instance = api->open("verified.so", mode, PACK_NATIVE_ABI_VERSION, &call);
        REQUIRE(instance != NULL);
        uint8_t *out = NULL;
        size_t length = 0;
        REQUIRE(api->invoke(instance, "hello", NULL, 0, &call, &out, &length) == 0);
        REQUIRE(out != NULL && length == sizeof(greeting) - 1);
        REQUIRE(memcmp(out, greeting, length) == 0);
        api->free_buffer(instance, out, length);
        REQUIRE(api->invoke(instance, "echo", json, sizeof(json) - 1, &call, &out, &length) == 0);
        REQUIRE(out != NULL && length == sizeof(json) - 1 && out != json);
        REQUIRE(memcmp(out, json, length) == 0);
        api->free_buffer(instance, out, length);
        /* Do not carry a freed pointer into a failure-path assertion. */
        out = NULL;
        length = 123;
        REQUIRE(api->invoke(instance, "missing", json, sizeof(json) - 1, &call, &out, &length) == PACK_NATIVE_ERR_INVALID_ARGUMENT);
        REQUIRE(out == NULL && length == 0);
        status = PACK_CALL_CANCELLED;
        length = 123;
        REQUIRE(api->invoke(instance, "hello", NULL, 0, &call, &out, &length) == PACK_NATIVE_ERR_CANCELLED);
        REQUIRE(out == NULL && length == 0);
        REQUIRE(api->open("verified.so", mode, PACK_NATIVE_ABI_VERSION, &call) == NULL);
        status = PACK_CALL_TIMEOUT;
        length = 123;
        REQUIRE(api->invoke(instance, "hello", NULL, 0, &call, &out, &length) == PACK_NATIVE_ERR_TIMEOUT);
        REQUIRE(out == NULL && length == 0);
        status = PACK_CALL_ACTIVE;
        REQUIRE(api->close(instance) == PACK_NATIVE_RELEASED);
    }
    puts("PASS: hello-native ABI version, buffers, cancellation, timeout and close");
    return 0;
}
