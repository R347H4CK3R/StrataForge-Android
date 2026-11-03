# StrataForge Android

**Application ID:** `dev.strataforge.android`.

This project is completely separate from StrataDroid (`dev.stratadroid.launcher`): independent repository, package name, storage, APK, and release lifecycle.

**Phase 1:** Game-file intake and Android environment diagnostics. Imports files using the Android system file picker, retains URI permissions and metadata, not game files. Reports CPU ABI, Android version, OpenGL ES version, and installed runtime availability. The initial APK does **not** execute Windows binaries or iOS IPA files. No emulation core has been integrated. A working game runtime requires an Android-compatible translation/graphics stack and device testing. A successful launcher APK build is not verification of game execution.

Build: GitHub Actions workflow **Build StrataForge Android**.
