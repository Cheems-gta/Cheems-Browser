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

## V1.0 — 文件原图标与搜索定位闪烁

- 已安装应用对应的 `Android/data/<package>`、`Android/obb/<package>`、`Android/media/<package>` 子目录：如果系统允许列出该目录且包名可见，尝试用 PackageManager 读取该应用的实际图标。
- `.apk` 文件：尝试从 APK 自身 AndroidManifest.xml 引用的资源加载应用图标，不安装 APK。加载不到会保留 CFM 默认图标。
- `.exe` / `.dll` 文件：尝试只读解析文件内的 Windows PE `RT_GROUP_ICON` / `RT_ICON` 资源（PNG 和常见 DIB 图标），不执行文件。部分打包程序、加密或非常见图标格式会回退普通图标；Windows PC 版则直接调用系统 Shell 获取原图标。
- 原图标使用可视区域惰性加载、每批至多 12 项和本地缓存，避免文件夹有数千项时阻塞列表。无网络访问。
- 点击搜索结果：跳转到文件所在目录、滚动到目标、文件名绿色提示约 2.6 秒，然后回到正常文字颜色。
- 注意：上述图标功能不会绕过 Android 13+ 对 `Android/data` 的目录访问限制。APP 读取已安装软件的图标元数据需要 `QUERY_ALL_PACKAGES` 可见性权限，此版本为用户自主安装测试版；提交应用商店前需审查相关政策和权限最小化。
