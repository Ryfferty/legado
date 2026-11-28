#include "pack_api_v1.h"

#include <jni.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SEAL 0x0001
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW 0x0004
#define F_SEAL_WRITE 0x0008
#endif

namespace {
JavaVM *vm = nullptr;
constexpr size_t MAX_LIBRARIES = 64;
constexpr uint32_t MAX_CALL_STATES = 64;
constexpr size_t MAX_CODE_BYTES = 64U * 1024U * 1024U;
constexpr size_t MAX_LOG_BYTES = 16384;

class Env {
public:
    JNIEnv *env = nullptr;
    bool attached = false;
    Env() {
        if (!vm) return;
        jint result = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
            else env = nullptr;
        } else if (result != JNI_OK) env = nullptr;
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};

struct Library {
    std::mutex gate;
    void *dl = nullptr;
    int fd = -1;
    void *instance = nullptr;
    pack_api_v1 api{};
    pack_host_api_v1 host{};
    jobject logger = nullptr;
    jmethodID log_method = nullptr;
    std::atomic<uint32_t> call_states{0};
    uint32_t active = 0;
    bool closing = false;
    bool failed = false;
    size_t max_output = 0;
    ~Library() {
        // Never implicitly dlclose: ABI close must first prove cooperative quiescence.
        if (fd >= 0) ::close(fd);
        Env attached;
        if (attached.env && logger) attached.env->DeleteGlobalRef(logger);
    }
};

std::mutex registry_gate;
std::unordered_map<jlong, std::shared_ptr<Library>> libraries;
// Failure latch by staged path: a library that was mapped and then failed
// keeps its registry entry until restart, so a retry must reuse that failure
// instead of mapping the same file again under a new handle/name until the
// 64-slot registry quota is exhausted.
std::unordered_map<std::string, std::string> failed_paths;
jlong next_handle = 1;

void throw_java(JNIEnv *env, const std::string &message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("io/legado/pack/runtime/nativebridge/NativeBridgeFailure");
    if (type) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

// Post-mapping failure protocol: the library stays dlopen'ed in this process
// (registry entry + per-path latch) until a restart, so Kotlin must see a
// distinct exception type to record residency on the pack record instead of
// reporting a clean release later.
void throw_java_resident(JNIEnv *env, const std::string &message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("io/legado/pack/runtime/nativebridge/NativeResidentFailure");
    if (type) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

std::string system_error(const char *operation) {
    return std::string(operation) + ": " + std::strerror(errno);
}

std::shared_ptr<Library> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registry_gate);
    auto it = libraries.find(id);
    if (it == libraries.end()) throw std::runtime_error("unknown/closed native handle");
    return it->second;
}

// call_v1 lives INSIDE its reference-counted state, not on an invoke stack.
struct CallState {
    pack_call_v1 abi{};
    std::atomic<uint64_t> refs{1};
    std::shared_ptr<Library> library;
    jobject object = nullptr;
    jmethodID check = nullptr;
    jmethodID now = nullptr;
};

int32_t check_active(void *opaque) {
    auto *state = static_cast<CallState *>(opaque);
    Env attached;
    if (!attached.env) return PACK_CALL_CANCELLED;
    jint result = attached.env->CallIntMethod(state->object, state->check);
    if (attached.env->ExceptionCheck()) {
        attached.env->ExceptionClear();
        return PACK_CALL_CANCELLED;
    }
    return result;
}

int64_t now_nanos(void *opaque) {
    auto *state = static_cast<CallState *>(opaque);
    Env attached;
    if (!attached.env) return state->abi.deadline_nanos;
    jlong result = attached.env->CallLongMethod(state->object, state->now);
    if (attached.env->ExceptionCheck()) {
        attached.env->ExceptionClear();
        return state->abi.deadline_nanos;
    }
    return result;
}

void retain_call(void *opaque) {
    auto *state = static_cast<CallState *>(opaque);
    state->refs.fetch_add(1, std::memory_order_relaxed);
}

void release_call(void *opaque) {
    auto *state = static_cast<CallState *>(opaque);
    if (state->refs.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        Env attached;
        if (attached.env) attached.env->DeleteGlobalRef(state->object);
        state->library->call_states.fetch_sub(1, std::memory_order_acq_rel);
        delete state;
    }
}

struct CallOwner {
    CallState *state = nullptr;
    CallOwner(JNIEnv *env, jobject call, const std::shared_ptr<Library> &library) {
        auto value = std::make_unique<CallState>();
        if (library->call_states.fetch_add(1, std::memory_order_acq_rel) >= MAX_CALL_STATES) {
            library->call_states.fetch_sub(1, std::memory_order_acq_rel);
            throw std::runtime_error("native retained-call quota exceeded; restart required");
        }
        value->library = library;
        value->object = env->NewGlobalRef(call);
        jclass cls = value->object ? env->GetObjectClass(call) : nullptr;
        value->check = cls ? env->GetMethodID(cls, "checkActiveState", "()I") : nullptr;
        value->now = value->check ? env->GetMethodID(cls, "nowNanos", "()J") : nullptr;
        jmethodID deadline = value->now ? env->GetMethodID(cls, "deadlineNanos", "()J") : nullptr;
        if (cls) env->DeleteLocalRef(cls);
        if (deadline && !env->ExceptionCheck()) value->abi.deadline_nanos = env->CallLongMethod(call, deadline);
        if (!value->object || !value->check || !value->now || !deadline || env->ExceptionCheck()) {
            if (value->object) env->DeleteGlobalRef(value->object);
            library->call_states.fetch_sub(1, std::memory_order_acq_rel);
            throw std::runtime_error("JNI call-state binding failed; check consumer rules");
        }
        value->abi.struct_size = sizeof(pack_call_v1);
        value->abi.call_handle = value.get();
        value->abi.check_active = check_active;
        value->abi.now_nanos = now_nanos;
        value->abi.retain_call = retain_call;
        value->abi.release_call = release_call;
        state = value.release();
    }
    ~CallOwner() { if (state) release_call(state); }
    CallOwner(const CallOwner &) = delete;
    CallOwner &operator=(const CallOwner &) = delete;
    void require_active() {
        int32_t result = check_active(state);
        if (result != PACK_CALL_ACTIVE) throw std::runtime_error(result == PACK_CALL_TIMEOUT ? "native call deadline expired" : "native call cancelled");
    }
};

void log_message(void *opaque, int32_t level, const uint8_t *bytes, size_t length) {
    auto *library = static_cast<Library *>(opaque);
    if (!bytes || length > MAX_LOG_BYTES) return;
    Env attached;
    if (!attached.env || !library->logger || !library->log_method) return;
    jbyteArray message = attached.env->NewByteArray(static_cast<jsize>(length));
    if (message) {
        attached.env->SetByteArrayRegion(message, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(bytes));
        if (!attached.env->ExceptionCheck()) attached.env->CallVoidMethod(library->logger, library->log_method, level, message);
        attached.env->DeleteLocalRef(message);
    }
    // Log callbacks cannot unwind a foreign C stack. The host logger owns budgets.
    if (attached.env->ExceptionCheck()) attached.env->ExceptionClear();
}

void read_exact(int fd, void *out, size_t count, off_t offset) {
    auto *target = static_cast<uint8_t *>(out);
    size_t done = 0;
    while (done < count) {
        ssize_t got = pread(fd, target + done, count - done, offset + static_cast<off_t>(done));
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) throw std::runtime_error("truncated/unreadable ELF component");
        done += static_cast<size_t>(got);
    }
}

#if defined(__aarch64__)
constexpr uint16_t PROCESS_MACHINE = EM_AARCH64;
#elif defined(__arm__)
constexpr uint16_t PROCESS_MACHINE = EM_ARM;
#elif defined(__x86_64__)
constexpr uint16_t PROCESS_MACHINE = EM_X86_64;
#elif defined(__i386__)
constexpr uint16_t PROCESS_MACHINE = EM_386;
#else
#error Unsupported Android process ABI
#endif

#if INTPTR_MAX == INT64_MAX
using ElfHeader = Elf64_Ehdr;
using ProgramHeader = Elf64_Phdr;
constexpr unsigned char PROCESS_ELF_CLASS = ELFCLASS64;
#else
using ElfHeader = Elf32_Ehdr;
using ProgramHeader = Elf32_Phdr;
constexpr unsigned char PROCESS_ELF_CLASS = ELFCLASS32;
#endif

void validate_elf(int fd, size_t size) {
    ElfHeader header{};
    if (size < sizeof(header)) throw std::runtime_error("native component is not an ELF shared object");
    read_exact(fd, &header, sizeof(header), 0);
    if (std::memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 || header.e_ident[EI_CLASS] != PROCESS_ELF_CLASS ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_type != ET_DYN || header.e_machine != PROCESS_MACHINE) {
        throw std::runtime_error("ELF ABI/class/machine does not match this running process");
    }
    if (header.e_phnum == 0 || header.e_phnum > 512 || header.e_phentsize != sizeof(ProgramHeader)) {
        throw std::runtime_error("invalid ELF program header table");
    }
    uint64_t end = static_cast<uint64_t>(header.e_phoff) + static_cast<uint64_t>(header.e_phnum) * sizeof(ProgramHeader);
    if (end > size) throw std::runtime_error("ELF program headers exceed the file");
    long page = sysconf(_SC_PAGESIZE);
    if (page < 4096) throw std::runtime_error("cannot determine actual device page size");
    bool load_segment = false;
    for (uint16_t i = 0; i < header.e_phnum; ++i) {
        ProgramHeader segment{};
        read_exact(fd, &segment, sizeof(segment), static_cast<off_t>(header.e_phoff + i * sizeof(segment)));
        if (segment.p_type != PT_LOAD) continue;
        load_segment = true;
        if (segment.p_filesz > size || segment.p_offset > size - segment.p_filesz) throw std::runtime_error("ELF load segment exceeds the file");
        if (segment.p_align < static_cast<uint64_t>(page) ||
            (segment.p_offset % page) != (segment.p_vaddr % page)) {
            throw std::runtime_error("ELF PT_LOAD alignment is incompatible with actual page size (including 16KB devices)");
        }
    }
    if (!load_segment) throw std::runtime_error("ELF has no loadable segment");
}

struct FileOwner {
    int fd;
    explicit FileOwner(int descriptor) : fd(descriptor) {}
    ~FileOwner() { if (fd >= 0) ::close(fd); }
};

int sealed_copy(int input, size_t size, CallOwner &call, int sdk) {
    if (sdk < 30) throw std::runtime_error("MEMFD requires Android API 30+; fallback is forbidden");
    // Resolve the public libc API only after the API gate; no private linker hooks.
    using MemfdCreate = int (*)(const char *, unsigned int);
    auto create = reinterpret_cast<MemfdCreate>(dlsym(RTLD_DEFAULT, "memfd_create"));
    if (!create) throw std::runtime_error("public memfd_create is unavailable on this device");
    FileOwner memory(create("legado-pack", MFD_CLOEXEC | MFD_ALLOW_SEALING));
    if (memory.fd < 0) throw std::runtime_error(system_error("memfd_create denied"));
    uint8_t buffer[8192];
    size_t position = 0;
    while (position < size) {
        call.require_active();
        size_t count = std::min(sizeof(buffer), size - position);
        read_exact(input, buffer, count, static_cast<off_t>(position));
        size_t written = 0;
        while (written < count) {
            ssize_t result = write(memory.fd, buffer + written, count - written);
            if (result < 0 && errno == EINTR) continue;
            if (result <= 0) throw std::runtime_error(system_error("memfd write"));
            written += static_cast<size_t>(result);
        }
        position += count;
    }
    // Android (API 36 emulator, google_apis) denies fchmod() on memfd files with
    // EACCES (strerror says "Permission denied"); the seals are the actual
    // immutability guarantee (F_SEAL_WRITE blocks future writable mappings and
    // truncation, F_SEAL_SEAL locks the seal set), so the mode bit is best-effort
    // only. Sealing itself stays fatal.
    if (fchmod(memory.fd, 0400) != 0 && errno != EACCES && errno != EPERM) {
        throw std::runtime_error(system_error("memfd fchmod read-only"));
    }
    if (fcntl(memory.fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) != 0) {
        throw std::runtime_error(system_error("memfd F_ADD_SEALS"));
    }
    int result = memory.fd;
    memory.fd = -1;
    return result;
}

struct ActiveInvocation {
    std::shared_ptr<Library> library;
    explicit ActiveInvocation(std::shared_ptr<Library> value) : library(std::move(value)) {
        std::lock_guard<std::mutex> lock(library->gate);
        if (library->closing || library->failed || library->active != 0) throw std::runtime_error("native component is closed, failed or already executing");
        library->active++;
    }
    ~ActiveInvocation() {
        std::lock_guard<std::mutex> lock(library->gate);
        library->active--;
    }
};

struct UtfChars {
    JNIEnv *env;
    jstring source;
    const char *value;
    UtfChars(JNIEnv *e, jstring s) : env(e), source(s), value(e->GetStringUTFChars(s, nullptr)) {
        if (!value) throw std::runtime_error("JNI string allocation failed");
    }
    ~UtfChars() { env->ReleaseStringUTFChars(source, value); }
};
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *value, void *) {
    vm = value;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_legado_pack_runtime_nativebridge_NativeBridge_open(JNIEnv *env, jobject, jstring path, jint mode,
        jint abi_version, jint sdk, jobject call, jobject logger, jint max_output) {
    std::shared_ptr<Library> library;
    jlong id = 0;
    std::string path_key;
    // Set when this open (or an earlier one for the same path) leaves a
    // dlopen'ed library in the process; the catch handlers then throw the
    // typed resident failure so Kotlin can record the residency.
    bool stays_mapped = false;
    try {
        if (!path || !call || !logger || abi_version != PACK_NATIVE_ABI_VERSION || max_output <= 0 ||
            (mode != PACK_NATIVE_MODE_FILE && mode != PACK_NATIVE_MODE_MEMFD)) throw std::runtime_error("invalid native open parameters/ABI version");
        UtfChars private_path(env, path);
        path_key = private_path.value;
        {
            // Failure latch: staged paths are content-addressed, so the same
            // path means the same bytes. Reuse the recorded failure instead of
            // mapping the library again.
            std::lock_guard<std::mutex> lock(registry_gate);
            auto prior = failed_paths.find(path_key);
            if (prior != failed_paths.end()) {
                // The mapping from the first failure is still alive, so the
                // retry must carry the residency signal as well.
                stays_mapped = true;
                throw std::runtime_error("native library already failed for this path and stays resident; restart required. First failure: " + prior->second);
            }
        }
        FileOwner input(::open(private_path.value, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
        if (input.fd < 0) throw std::runtime_error(system_error("open private code"));
        struct stat facts{};
        if (fstat(input.fd, &facts) != 0 || !S_ISREG(facts.st_mode) || (facts.st_mode & 0222) || facts.st_uid != getuid() ||
            facts.st_size <= 0 || static_cast<uint64_t>(facts.st_size) > MAX_CODE_BYTES) throw std::runtime_error("native code must be app-owned, read-only, regular and <=64MiB");
        validate_elf(input.fd, static_cast<size_t>(facts.st_size));
        library = std::make_shared<Library>();
        library->max_output = static_cast<size_t>(max_output);
        library->logger = env->NewGlobalRef(logger);
        jclass logger_cls = library->logger ? env->GetObjectClass(logger) : nullptr;
        library->log_method = logger_cls ? env->GetMethodID(logger_cls, "log", "(I[B)V") : nullptr;
        if (logger_cls) env->DeleteLocalRef(logger_cls);
        if (!library->logger || !library->log_method || env->ExceptionCheck()) throw std::runtime_error("JNI logger binding failed; check consumer rules");
        library->host.struct_size = sizeof(pack_host_api_v1);
        library->host.host = library.get();
        library->host.log = log_message;
        CallOwner first(env, call, library);
        first.require_active();
        if (mode == PACK_NATIVE_MODE_MEMFD) library->fd = sealed_copy(input.fd, static_cast<size_t>(facts.st_size), first, sdk);
        {
            std::lock_guard<std::mutex> lock(registry_gate);
            if (libraries.size() >= MAX_LIBRARIES) throw std::runtime_error("native library registry quota exceeded; restart required");
            id = next_handle++;
            libraries.emplace(id, library);
        }
        ActiveInvocation active(library);
        first.require_active();
        dlerror();
        // Both modes load the verified descriptor directly. A /proc/self/fd
        // pathname can be canonicalized/reopened by the linker. FORCE_LOAD keeps
        // distinct pack revisions from aliasing an already loaded SONAME.
        android_dlextinfo info{};
        info.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_FORCE_LOAD;
        info.library_fd = mode == PACK_NATIVE_MODE_MEMFD ? library->fd : input.fd;
        std::string name = "legado-pack-" + std::to_string(id) + ".so";
        library->dl = android_dlopen_ext(name.c_str(), RTLD_NOW | RTLD_LOCAL, &info);
        if (!library->dl) {
            const char *error = dlerror();
            throw std::runtime_error(std::string("dlopen denied/missing dependency/ELF failure: ") + (error ? error : "unknown linker error") + "; mode unchanged; SELinux/linker namespace policy requires device validation");
        }
        // dlopen constructors and entry discovery are arbitrary trusted code.
        // Before a valid ABI exists, no safe destructor/thread cleanup can be proven.
        first.require_active();
        dlerror();
        auto entry = reinterpret_cast<pack_get_api_v1_fn>(dlsym(library->dl, PACK_NATIVE_ENTRY_SYMBOL));
        const char *symbol_error = dlerror();
        if (!entry || symbol_error) throw std::runtime_error("missing pack_get_api_v1 symbol; mapped library is pinned until restart");
        const pack_api_v1 *api = entry(PACK_NATIVE_ABI_VERSION, &library->host, &library->api);
        if (!api || api->struct_size < sizeof(pack_api_v1)) throw std::runtime_error("invalid native ABI 1 table/size; library remains resident");
        // The table may be in pack memory; copy it before the next callback.
        library->api = *api;
        if (!library->api.open || !library->api.invoke || !library->api.free_buffer || !library->api.close) throw std::runtime_error("native ABI 1 table has missing callbacks; library remains resident");
        first.require_active();
        library->instance = library->api.open(private_path.value, mode, abi_version, &first.state->abi);
        if (!library->instance) throw std::runtime_error("native ABI open returned null; library remains resident");
        // Return ownership even if the deadline just expired. Kotlin checks the
        // original call and queues cooperative cleanup instead of losing the handle.
        return id;
    } catch (const std::exception &error) {
        if (library && id) {
            if (library->dl) {
                {
                    std::lock_guard<std::mutex> lock(library->gate);
                    library->failed = true;
                }
                // Latch the failure for this path so retries reuse this entry
                // instead of consuming another registry slot.
                std::lock_guard<std::mutex> lock(registry_gate);
                failed_paths[path_key] = error.what();
                // dlopen succeeded before the failure, so the mapping (and
                // this registry entry) is pinned until restart.
                stays_mapped = true;
            } else {
                std::lock_guard<std::mutex> lock(registry_gate);
                libraries.erase(id);
            }
        }
        if (stays_mapped) throw_java_resident(env, error.what());
        else throw_java(env, error.what());
        return 0;
    } catch (...) {
        if (library && id) {
            if (library->dl) {
                {
                    std::lock_guard<std::mutex> lock(library->gate);
                    library->failed = true;
                }
                std::lock_guard<std::mutex> lock(registry_gate);
                failed_paths[path_key] = "native code threw across the ABI boundary";
                stays_mapped = true;
            } else {
                std::lock_guard<std::mutex> lock(registry_gate);
                libraries.erase(id);
            }
        }
        if (stays_mapped) throw_java_resident(env, "native code threw across ABI boundary; restart required");
        else throw_java(env, "native code threw across ABI boundary; restart required");
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_legado_pack_runtime_nativebridge_NativeBridge_invoke(JNIEnv *env, jobject, jlong id,
        jstring command, jbyteArray argument, jobject call) {
    try {
        if (!command || !argument || !call) throw std::runtime_error("invalid native invoke arguments");
        auto library = lookup(id);
        ActiveInvocation active(library);
        CallOwner current(env, call, library);
        current.require_active();
        UtfChars name(env, command);
        jsize length = env->GetArrayLength(argument);
        if (length < 0 || static_cast<size_t>(length) > library->max_output) throw std::runtime_error("native input JSON limit exceeded");
        std::vector<uint8_t> input(static_cast<size_t>(length));
        if (length) env->GetByteArrayRegion(argument, 0, length, reinterpret_cast<jbyte *>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        uint8_t *output = nullptr;
        size_t output_size = 0;
        int32_t result = library->api.invoke(library->instance, name.value, input.data(), input.size(), &current.state->abi, &output, &output_size);
        struct BufferOwner {
            Library *library;
            uint8_t *pointer;
            size_t length;
            ~BufferOwner() { if (pointer) library->api.free_buffer(library->instance, pointer, length); }
        } buffer{library.get(), output, output_size};
        if (result != 0) throw std::runtime_error("native invoke status=" + std::to_string(result) + ((output || output_size) ? "; ABI violation: output on failure" : ""));
        current.require_active();
        if (!output || output_size == 0 || output_size > library->max_output || output_size > static_cast<size_t>(std::numeric_limits<jsize>::max())) throw std::runtime_error("invalid/oversized native output buffer");
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(output_size));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output_size), reinterpret_cast<const jbyte *>(output));
        return bytes;
    } catch (const std::exception &error) {
        throw_java(env, error.what());
        return nullptr;
    } catch (...) {
        throw_java(env, "native invoke threw across ABI boundary; trusted process may require restart");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_legado_pack_runtime_nativebridge_NativeBridge_close(JNIEnv *env, jobject, jlong id) {
    try {
        auto library = lookup(id);
        {
            std::lock_guard<std::mutex> lock(library->gate);
            // Check all references before calling package close. No mutex is held
            // while package code executes, logs or releases a retained call.
            if (library->failed || library->active != 0 || library->call_states.load(std::memory_order_acquire) != 0 || library->closing) return PACK_NATIVE_STILL_RESIDENT;
            library->closing = true;
        }
        int32_t result = library->api.close(library->instance);
        if (result != PACK_NATIVE_RELEASED || library->call_states.load(std::memory_order_acquire) != 0) {
            // Keep closed for new invokes, mapped for outstanding native references.
            return PACK_NATIVE_STILL_RESIDENT;
        }
        // dlclose may run uncooperative destructors. Kotlin invokes this method
        // only on the bounded cleanup worker, never while holding a host lock.
        if (dlclose(library->dl) != 0) {
            const char *error = dlerror();
            throw std::runtime_error(std::string("dlclose failed: ") + (error ? error : "unknown"));
        }
        library->dl = nullptr;
        {
            std::lock_guard<std::mutex> lock(registry_gate);
            libraries.erase(id);
        }
        return PACK_NATIVE_RELEASED;
    } catch (const std::exception &error) {
        throw_java(env, error.what());
        return PACK_NATIVE_STILL_RESIDENT;
    } catch (...) {
        throw_java(env, "native close threw across ABI boundary; library remains resident");
        return PACK_NATIVE_STILL_RESIDENT;
    }
}
