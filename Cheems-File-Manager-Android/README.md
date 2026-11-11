# Cheems File Manager — Android V0.1

Android native trial version of CFM, written in Java, built with Android SDK.

This is a separate project from the Windows WPF EXE. WPF EXE cannot simply be converted to an APK.

## What works (experimental)
- Dual-pane folder browser: portrait = top/bottom, landscape = left/right
- Android Storage Access Framework directory picker, persistent read/write permissions
- Navigate into folder and back to chosen root
- Select a file (tap) or folder (long-press), copy selection to other pane
- Create a folder, rename a file/folder
- Edit common UTF-8 text files up to 256 KB
- Offline, no ads, no account, no INTERNET permission, no ROOT required

## Limitations and safety
- For safety, V0.1 intentionally omits delete and move.
- User must explicitly choose authorized folders in Android picker; Android blocks some directories (e.g. other apps' private data, Android/data). This app does not bypass system protections.
- The UI lists up to 300 items/folder for initial tests.
- Recursive copy only supports up to 30 folder levels. Large copies may freeze UI; interrupted copies can leave partial files.
- No support for ZIP, APK analysis, DEX editing, hex editing, or root file access yet.
- App is unsigned for distribution; debug APK is signed using standard Android debug key. Never override device security warnings to install unknown files; use test devices/folders and verify source.
- Compilation on GitHub does **not** equal device-tested features.

## Build APK
Check `.github/workflows/cfm-android-build.yml` on the `cfm-android-v0.1` branch. When the push build succeeds, GitHub Actions → run → Artifacts → `CheemsFM-Android-V0.1-debug-apk`.
To compile locally:

```sh
gradle -p Cheems-File-Manager-Android :app:assembleDebug
```

Output: `Cheems-File-Manager-Android/app/build/outputs/apk/debug/app-debug.apk`

## Repo layout
This app is temporarily isolated on the `cfm-android-v0.1` branch in the existing Cheems-Browser repository; it does not change `main` or the Windows CFM branch.
