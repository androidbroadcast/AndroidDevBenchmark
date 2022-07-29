# Changelog

Report issues to [GitHub].

For Android Studio issues, follow the docs on the [Android Studio site].

[GitHub]: https://github.com/android/ndk/issues
[Android Studio site]: http://tools.android.com/filing-bugs

## Announcements

* GNU binutils, excluding the GNU Assembler (GAS), has been removed. GAS will be
  removed in the next release. If you are building with `-fno-integrated-as`,
  file bugs if anything is preventing you from removing that flag.

* Support for GDB has ended. GDB will be removed from the next release. Use LLDB
  instead. Note that `ndk-gdb` uses LLDB by default.

* NDK r23 is the last release that will support non-Neon. Beginning with NDK
  r24, the armeabi-v7a libraries in the sysroot will be built with Neon. A very
  small number of very old devices do not support Neon so most apps will not
  notice aside from the performance improvement.

* Jelly Bean (APIs 16, 17, and 18) will not be supported in the next NDK
  release. The minimum OS supported by the NDK for r24 will be KitKat (API level
  19).

## Changes

* Includes preview Android S Beta 1 APIs.
* Updated LLVM to clang-r416183b, based on LLVM 12 development.
  * [Issue 1047]: Fixes crash when using ASan with the CFI unwinder.
  * [Issue 1096]: Includes support for [Polly]. Enable by adding `-mllvm -polly`
    to your cflags.
  * [Issue 1230]: LLVM's libunwind is now used instead of libgcc for all
    architectures rather than just 32-bit Arm.
  * [Issue 1231]: LLVM's libclang_rt.builtins is now used instead of libgcc.
  * [Issue 1406]: Fixes crash with Neon intrinsic.
* Vulkan validation layer source and binaries are no longer shipped in the NDK.
  The latest are now posted directly to [GitHub](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases).
* Vulkan tools source is also removed, specifically vulkan_wrapper.
  It should be downloaded upstream from [GitHub](https://github.com/KhronosGroup/Vulkan-Tools).
* The toolchain file (android.toolchain.cmake) is refactored to base on cmake's
  integrated Android support. This new toolchain file will be enabled by default
  for cmake 3.21 and newer. No user side change is expected. But if anything goes
  wrong, please file a bug and set `ANDROID_USE_LEGACY_TOOLCHAIN_FILE=ON` to
  restore the legacy behavior.
* [Issue 929]: `find_library` now prefers shared libraries from the sysroot over
  static libraries.
* [Issue 1390]: ndk-build now warns when building a static executable with the
  wrong API level.
* [Issue 1452]: `NDK_ANALYZE=1` now sets `APP_CLANG_TIDY=true` rather than using
  scan-build. clang-tidy performs all the same checks by default, and scan-build
  was no longer working. See the bug for more details, but no user-side changes
  should be needed.

[Issue 929]: https://github.com/android/ndk/issues/929
[Issue 1047]: https://github.com/android/ndk/issues/1047
[Issue 1096]: https://github.com/android/ndk/issues/1096
[Issue 1230]: https://github.com/android/ndk/issues/1230
[Issue 1231]: https://github.com/android/ndk/issues/1231
[Issue 1390]: https://github.com/android/ndk/issues/1390
[Issue 1406]: https://github.com/android/ndk/issues/1406
[Issue 1452]: https://github.com/android/ndk/issues/1452
[Polly]: https://polly.llvm.org/

## Known Issues

* This is not intended to be a comprehensive list of all outstanding bugs.
* [Issue 360]: `thread_local` variables with non-trivial destructors will cause
  segfaults if the containing library is `dlclose`ed on devices running M or
  newer, or devices before M when using a static STL. The simple workaround is
  to not call `dlclose`.
* [Issue 906]: Clang does not pass `-march=armv7-a` to the assembler when using
  `-fno-integrated-as`. This results in the assembler generating ARMv5
  instructions. Note that by default Clang uses the integrated assembler which
  does not have this problem. To workaround this issue, explicitly use
  `-march=armv7-a` when building for 32-bit ARM with the non-integrated
  assembler, or use the integrated assembler. ndk-build and CMake already
  contain these workarounds.
* [Issue 988]: Exception handling when using ASan via wrap.sh can crash. To
  workaround this issue when using libc++_shared, ensure that your
  application's libc++_shared.so is in `LD_PRELOAD` in your `wrap.sh` as in the
  following example:

  ```bash
  #!/system/bin/sh
  HERE="$(cd "$(dirname "$0")" && pwd)"
  export ASAN_OPTIONS=log_to_syslog=false,allow_user_segv_handler=1
  ASAN_LIB=$(ls $HERE/libclang_rt.asan-*-android.so)
  if [ -f "$HERE/libc++_shared.so" ]; then
      # Workaround for https://github.com/android/ndk/issues/988.
      export LD_PRELOAD="$ASAN_LIB $HERE/libc++_shared.so"
  else
      export LD_PRELOAD="$ASAN_LIB"
  fi
  "$@"
   ```

  There is no known workaround for libc++_static.

  Note that because this is a platform bug rather than an NDK bug this
  workaround will be necessary for this use case to work on all devices until
  at least Android R.
* [Issue 1130]: When using `c++_static` and the deprecated linker with ndk-build
  with an `APP_PLATFORM` below 21, undefined references to operator new may
  occur. The fix is to use LLD.
* This version of the NDK is incompatible with the Android Gradle plugin
  version 3.0 or older. If you see an error like
  `No toolchains found in the NDK toolchains folder for ABI with prefix: mips64el-linux-android`,
  update your project file to [use plugin version 3.1 or newer]. You will also
  need to upgrade to Android Studio 3.1 or newer.
* [Issue 843]: Using LLD with binutils `strip` or `objcopy` breaks RelRO. Use
   `llvm-strip` and `llvm-objcopy` instead. This issue has been resolved in
   Android Gradle Plugin version 4.0 (for non-Gradle users, the fix is also in
   ndk-build and our CMake toolchain file), but may affect other build systems.

[Issue 360]: https://github.com/android/ndk/issues/360
[Issue 843]: https://github.com/android/ndk/issues/843
[Issue 906]: https://github.com/android/ndk/issues/906
[Issue 988]: https://github.com/android/ndk/issues/988
[Issue 1130]: https://github.com/android/ndk/issues/1130
[use plugin version 3.1 or newer]: https://developer.android.com/studio/releases/gradle-plugin#updating-plugin
