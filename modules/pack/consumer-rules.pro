# External pack DEX is compiled against this module's public API as compileOnly
# and loaded by the host at runtime. Keep the API class/interface names and
# members stable; the pack DEX cannot see the host's R8 mapping.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class io.legado.pack.api.** { *; }
-keep interface io.legado.pack.api.** { *; }

# The native bridge library resolves these JNI-only classes and members by
# name from C code (see NativePackRuntimeProvider); R8 must not rename or
# remove them, or every native pack call fails at runtime in release builds.
-keep class io.legado.pack.runtime.nativebridge.NativeBridge { *; }
-keep class io.legado.pack.runtime.nativebridge.NativeCallState { *; }
-keep class io.legado.pack.runtime.nativebridge.NativeLogSink { *; }
# Constructed by name from C++ (FindClass + ThrowNew) on native failures; the
# Kotlin catch sites alone do not keep the class or its String constructor.
-keep class io.legado.pack.runtime.nativebridge.NativeBridgeFailure { *; }
# Resident subtype thrown by name from C++ (FindClass + ThrowNew) when a
# mapped-but-failed library stays in the native registry until restart.
-keep class io.legado.pack.runtime.nativebridge.NativeResidentFailure { *; }
