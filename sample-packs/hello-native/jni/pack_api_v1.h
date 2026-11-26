/*
 * Legado pack native ABI 1 (fixed contract).
 *
 * A native component exports exactly:
 *
 *   const pack_api_v1* pack_get_api_v1(int32_t host_abi_version,
 *                                      const pack_host_api_v1* host,
 *                                      pack_api_v1* out_api);
 *
 * The host bridge loads the .so from an already-verified read-only private file
 * (FILE mode) or from a sealed memfd (MEMFD mode, API 30+), then resolves this
 * symbol with dlsym. A native component without this exact symbol is not an
 * ABI-1 pack; a JNI_OnLoad-only library is not a substitute.
 *
 * Per-call context and lifetime:
 *  - Every invoke receives a pack_call_v1 owned by the host bridge. The host
 *    holds one reference for the duration of invoke. Native code must call
 *    retain_call() before handing the pointer to another thread or retaining it
 *    after invoke returns, and call release_call() exactly once when that use
 *    ends. check_active()/now_nanos() may be used from any native thread while
 *    the call has a reference.
 *  - The host must keep the call state, cancellation signal and deadline alive
 *    until the native reference count reaches zero. Timeout/cancellation does
 *    not authorize the native side to use a released call. If native code
 *    outlives invoke without releasing, the bridge reports resident/NEEDS_RESTART
 *    instead of freeing state out from under it.
 *  - check_active() must be thread-safe (atomic/acquire semantics on the host
 *    side). It returns PACK_CALL_ACTIVE, PACK_CALL_CANCELLED or PACK_CALL_TIMEOUT.
 *  - now_nanos() returns the same signed monotonic clock source used by Kotlin
 *    PackCallContext.deadlineNanos (System.nanoTime). deadline_nanos is a
 *    signed int64 bit pattern from that source. Expiry is defined exactly as:
 *
 *        (int64_t)((uint64_t)now_nanos - (uint64_t)deadline_nanos) >= 0
 *
 *    (two's-complement wrap-safe; signed subtraction overflow must be avoided).
 *    Native code must not substitute wall-clock time.
 *
 *  - open() receives the same first-call context as the first invoke. Native
 *    open-time verification/initialization must use the same deadline and
 *    cancellation; it may retain the call before returning if it starts a
 *    worker. open() returns an opaque handle owned by the host bridge.
 *  - invoke() writes a newly allocated UTF-8 JSON buffer. The host copies the
 *    bytes, then calls free_buffer(). On a negative status invoke() must leave
 *    *out_json == NULL and *out_len == 0.
 *  - invoke() must not throw C++ exceptions across this C boundary.
 *  - close() returns PACK_NATIVE_RELEASED only when no call, callback, thread,
 *    retained call context or registration still references the library.
 *    Otherwise it returns PACK_NATIVE_STILL_RESIDENT; the host must not dlclose.
 *  - No symbol from this ABI may be called after a successful close().
 */
#ifndef IO_LEGADO_PACK_API_V1_H
#define IO_LEGADO_PACK_API_V1_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PACK_NATIVE_ABI_VERSION 1
#define PACK_NATIVE_ENTRY_SYMBOL "pack_get_api_v1"

#define PACK_NATIVE_MODE_FILE 0
#define PACK_NATIVE_MODE_MEMFD 1

#define PACK_NATIVE_OPEN_OK 0
#define PACK_NATIVE_RELEASED 0
#define PACK_NATIVE_STILL_RESIDENT 1

#define PACK_NATIVE_ERR_INVALID_ARGUMENT (-1)
#define PACK_NATIVE_ERR_LOAD_FAILED (-2)
#define PACK_NATIVE_ERR_CANCELLED (-3)
#define PACK_NATIVE_ERR_TIMEOUT (-4)

#define PACK_CALL_ACTIVE 0
#define PACK_CALL_CANCELLED 1
#define PACK_CALL_TIMEOUT 2

typedef struct pack_host_api_v1 {
    uint32_t struct_size;
    void *host;

    /* level: 0 debug, 1 info, 2 warn, 3 error. message is UTF-8, not NUL terminated. */
    void (*log)(void *host, int32_t level, const uint8_t *message, size_t length);
} pack_host_api_v1;

typedef struct pack_call_v1 {
    uint32_t struct_size;
    void *call_handle;

    /* Thread-safe. Returns PACK_CALL_ACTIVE/CANCELLED/TIMEOUT. */
    int32_t (*check_active)(void *call_handle);

    /* Thread-safe. Signed System.nanoTime-compatible monotonic source. */
    int64_t (*now_nanos)(void *call_handle);

    /* Signed bit pattern from the same source; compare using the wrap-safe rule above. */
    int64_t deadline_nanos;

    /* Thread-safe reference count for use from native worker threads. */
    void (*retain_call)(void *call_handle);

    /* Thread-safe release; the last release frees the host call state. */
    void (*release_call)(void *call_handle);
} pack_call_v1;

typedef struct pack_api_v1 {
    uint32_t struct_size;

    void *(*open)(const char *verified_private_path,
                  int32_t mode,
                  int32_t abi_version,
                  const pack_call_v1 *call);

    int32_t (*invoke)(void *handle,
                      const char *command,
                      const uint8_t *arg_json,
                      size_t arg_len,
                      const pack_call_v1 *call,
                      uint8_t **out_json,
                      size_t *out_len);

    void (*free_buffer)(void *handle, uint8_t *out_json, size_t out_len);

    /* Returns PACK_NATIVE_RELEASED, PACK_NATIVE_STILL_RESIDENT or a negative error. */
    int32_t (*close)(void *handle);
} pack_api_v1;

typedef const pack_api_v1 *(*pack_get_api_v1_fn)(
        int32_t host_abi_version,
        const pack_host_api_v1 *host,
        pack_api_v1 *out_api);

/* The only symbol the bridge resolves. */
const pack_api_v1 *pack_get_api_v1(int32_t host_abi_version,
                                   const pack_host_api_v1 *host,
                                   pack_api_v1 *out_api);

#ifdef __cplusplus
}
#endif

#endif /* IO_LEGADO_PACK_API_V1_H */