# Cheems File Manager PC UI · Android Preview V0.1

The **complete Windows PC interface** is packaged as an Android APK, using the exact PC project's original style.css and app.js with no mobile redesign or cropping. The index.html viewport is the only visual adjustment: 1200 CSS pixels scaled to the phone viewport. Pinch to zoom or rotate to landscape to see larger text.

## Original design
- Own HTML/CSS/JS copied from branch `cfm-pc-web-v0.1` of this repository. No MT Manager proprietary UI files or code.
- All PC menu buttons, two file panes, path bars, dark style and original interactions are preserved.
- Runs locally in Android WebView, with no account, no network permission, no ads.
- JavaScript bridge connects most original file actions to local Android shared storage after Android grants the one-time "Manage all files" permission.

## Operations
Browse/filter/search, copy, move, rename, new folders, edit UTF-8 files and sandbox preview of HTML, ZIP compress and supported archive extraction, local trash.

**Important:** The original desktop button labeled 回收站 now uses `/storage/emulated/0/.CFM_RecycleBin` (moves files there). Recovery is manual through that folder. This is not the Windows Recycle Bin. For Android, the Home and Desktop shortcuts both navigate to shared storage root; Downloads goes to `Download`.

Protected Android/data and Android/obb folders are still inaccessible on many phones; Shizuku is not part of this particular PC interface preview. Large archives and special files may be unsupported. Test with disposable files. This debug APK is unsigned by a trusted publisher, and CI build success is not Android-device runtime testing.

## Build
GitHub branch `cfm-pc-android-preview-v0.1`, Actions workflow `.github/workflows/cfm-android-build.yml`. The APK installs **alongside** native Android Cheems File Manager because its application ID is different: `win.cheems.filemanager.pcpreview`.
