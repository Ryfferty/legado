/* Minimal, real ABI-1 pack. Build with the NDK for each requested device ABI. */
#include "pack_api_v1.h"
#include <stdlib.h>
#include <string.h>

typedef struct hello_instance {
    unsigned int magic;
} hello_instance;

static int32_t active_status(const pack_call_v1 *call) {
    if (!call || call->struct_size < sizeof(pack_call_v1) || !call->check_active) return PACK_NATIVE_ERR_INVALID_ARGUMENT;
    int32_t status = call->check_active(call->call_handle);
    if (status == PACK_CALL_CANCELLED) return PACK_NATIVE_ERR_CANCELLED;
    if (status == PACK_CALL_TIMEOUT) return PACK_NATIVE_ERR_TIMEOUT;
    return status == PACK_CALL_ACTIVE ? 0 : PACK_NATIVE_ERR_INVALID_ARGUMENT;
}

static void *hello_open(const char *path, int32_t mode, int32_t version, const pack_call_v1 *call) {
    if (!path || (mode != PACK_NATIVE_MODE_FILE && mode != PACK_NATIVE_MODE_MEMFD) ||
        version != PACK_NATIVE_ABI_VERSION || active_status(call) != 0) return NULL;
    hello_instance *instance = (hello_instance *)calloc(1, sizeof(hello_instance));
    if (instance) instance->magic = 0x5041434bU;
    return instance;
}

static int32_t hello_invoke(void *handle, const char *command, const uint8_t *arg, size_t arg_len,
                           const pack_call_v1 *call, uint8_t **out, size_t *out_len) {
    if (!out || !out_len) return PACK_NATIVE_ERR_INVALID_ARGUMENT;
    *out = NULL;
    *out_len = 0;
    hello_instance *instance = (hello_instance *)handle;
    if (!instance || instance->magic != 0x5041434bU || !command) return PACK_NATIVE_ERR_INVALID_ARGUMENT;
    int32_t status = active_status(call);
    if (status != 0) return status;
    const uint8_t *bytes;
    size_t length;
    static const char greeting[] = "{\"ok\":true,\"runtime\":\"native\",\"abiVersion\":1}";
    if (strcmp(command, "hello") == 0) {
        bytes = (const uint8_t *)greeting;
        length = sizeof(greeting) - 1;
    } else if (strcmp(command, "echo") == 0 && arg && arg_len > 0) {
        bytes = arg;
        length = arg_len;
    } else return PACK_NATIVE_ERR_INVALID_ARGUMENT;
    uint8_t *result = (uint8_t *)malloc(length);
    if (!result) return PACK_NATIVE_ERR_LOAD_FAILED;
    memcpy(result, bytes, length);
    status = active_status(call);
    if (status != 0) {
        free(result);
        return status;
    }
    *out = result;
    *out_len = length;
    return 0;
}

static void hello_free(void *handle, uint8_t *buffer, size_t length) {
    (void)handle;
    (void)length;
    free(buffer);
}

static int32_t hello_close(void *handle) {
    hello_instance *instance = (hello_instance *)handle;
    if (!instance || instance->magic != 0x5041434bU) return PACK_NATIVE_ERR_INVALID_ARGUMENT;
    instance->magic = 0;
    free(instance);
    return PACK_NATIVE_RELEASED;
}

#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
const pack_api_v1 *pack_get_api_v1(int32_t version, const pack_host_api_v1 *host, pack_api_v1 *out) {
    if (version != PACK_NATIVE_ABI_VERSION || !host || host->struct_size < sizeof(pack_host_api_v1) || !out) return NULL;
    memset(out, 0, sizeof(*out));
    out->struct_size = sizeof(*out);
    out->open = hello_open;
    out->invoke = hello_invoke;
    out->free_buffer = hello_free;
    out->close = hello_close;
    return out;
}
