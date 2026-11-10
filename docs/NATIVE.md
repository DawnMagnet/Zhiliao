# Native code

`app/src/main/cpp/zhiliao.cpp` is the only C++ in this module. It stays because the same behavior cannot be implemented in Kotlin.

## What it does

`MainHook.initNative` is called only when `Helper.checkSignature` says the running Zhihu package is not the official build. The library then inline-hooks libc `fopen` with Dobby:

- If the path ends in `base.apk`, the hook removes itself and exits the calling thread with `pthread_exit`.
- Every other `fopen` is forwarded.

Repackaged or modified Zhihu builds read their own `base.apk` from native code (packers and integrity checks). That call never enters `java.io.File`, so a Kotlin or Java hook cannot see it. Inline hooking libc and stopping that thread both require native code.

Officially signed Zhihu never loads this library.

## What is not our C++

DexKit ships its own `libdexkit.so`. The module loads that library by absolute path from the module APK (`DexKitFallback`). It is a dependency, not source in this repository, and it stays because scanning dex by string and method signature is the last-resort resolver when the candidate list and the structural search both miss.

`-PskipNativeBuild` skips CMake for JVM unit tests and the daily compat patrol. Release builds still compile `libzhiliao.so` for `arm64-v8a` and `armeabi-v7a`.
