# Cheems File Manager · PC V0.1

A **new, independently written** Windows file manager inspired by the practical, dark two-pane workflow of Android file managers. It does **not** redistribute MT Manager code, icons, HTML/JS, resources, or paid features.

## What this actually is
- **Windows EXE:** .NET 10 WPF app embedding a WebView2 UI.
- **HTML/CSS/JavaScript:** our own minimal dark desktop UI, bundled as embedded resources in the EXE.
- **C#:** native filesystem access, copy/move, rename, folder creation, recycle bin, search, archive compression/extraction, and text editing.
- No account, ads, tracking, server, or INTERNET requests from app UI.
- Windows x64, Windows 10/11; requires installed **Microsoft Edge WebView2 Runtime** on the machine (commonly preinstalled on Windows 11 but not guaranteed).

## Implemented operations
- Two independent panes with path fields, folders first, quick links to desktop/documents/downloads/drives.
- Double click folder to enter; click to select; Ctrl+click for multi-select; right-click for context menu; Backspace to go to parent.
- Copy/move selected items to the other pane (no silent overwrites; generates numbered target names).
- Create folder, rename, and send selected files/folders to Windows Recycle Bin after confirmation.
- File name filter per pane; bounded recursive search of current directory (up to 50k scanned entries).
- Inline UTF-8 editor (text, HTML, JSON, XML, JavaScript, etc., max 2MB); saves a `.cfm.bak` backup.
- For HTML, preview via a sandboxed iframe with Content Security Policy. Scripts, forms, external fetching and navigation are blocked in preview.
- Make ZIP from selections; extract ZIP, 7z, RAR, TAR, GZIP and other formats supported by SharpCompress (depends on archive and compression algorithm). Extraction prevents path traversal, is limited to 4,000 items, 512MB/file, 2GB total. Incomplete extraction may leave partial folders.

## Limitations / warnings
- Not a direct port of MT Manager and not a complete replacement for its APK/DEX editors. APK inspection and hexadecimal editing are not in V0.1.
- Windows native filesystem operations may fail on protected files, hard-linked/symlink folders, or cross-volume directory moves. Run without administrator rights. Use test folders for early testing.
- Never use unknown archives for testing. Archive format support varies (encrypted and split archives may not work). The preview is not a full, unrestricted browser.
- EXE is **unsigned**; Windows may show an unknown publisher warning. Do not disable Microsoft Defender or SmartScreen to run it.
- GitHub Actions building the EXE does **not** prove every operation works on an actual Windows PC.

## How to build
GitHub Actions workflow `.github/workflows/cfm-pc-build.yml` builds Windows x64:

```powershell
dotnet publish ./Cheems-File-Manager-PC/CheemsFileManagerPC.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o ./dist
```

Download the artifact `CheemsFileManager-PC-V0.1-win-x64` after the build; the archive contains the program files and EXE. The WPF application owns the embedded UI; no local web server or second browser process to configure.

Source branch: `cfm-pc-web-v0.1` (independent from browser, Android CFM, and the earlier Windows WPF experiment branches). The existing repo `main` branch is left intact.

## V0.8 — EXE 原始图标、APK 内置图标与搜索定位

- Windows `.exe` / `.dll` / `.msi`：通过 Windows Shell 读取对应文件的图标并在文件列表显示；非联网查询。
- `.apk`：直接查看 ZIP 资源中常见的 `res/mipmap*/ic_launcher.*`、`app_icon.*` 等图像。此方法是最佳匹配而非完整解析 Android ARSC 资源索引，某些 APK 会回退默认图标。
- 图标按实际可视文件惰性加载，单批最多 12 条，并按路径、大小和修改时间缓存，减少卡顿。
- 点击搜索结果后会进入该文件的上级目录，自动滚动至匹配条目；文件名变绿色约 2.6 秒后恢复普通文字颜色。搜索文件夹时也会先显示它所在的父目录。
- 不执行 EXE、APK，也不读取网络图标；只有 Windows PC 版可直接调用系统原生 EXE 图标。

## V1.0 · Windows PC 发布候选版

**架构不变：** 原 PC V0.8 的 .NET 10 WPF + WebView2、Windows x64 自包含单文件 EXE，保留独立桌面双栏、历史导航、右栏对齐左栏、新建、搜索结果定位高亮、EXE/APK 原生图标、书签、复制/移动/重命名、压缩/解压、文本及 HTML 安全预览、Windows CMD 终端、打开方式、系统分享、SHA-256、数字签名信息等。

**新增/补齐 Android V1.5 的操作：** 左右双栏多选、Ctrl+单击勾选、最多 200 项、当前栏全选、取消选择、长按/右键文件操作、底部常驻批量操作区，左右栏合并批量移入回收站，回收站中批量恢复或永久删除（单次永久删除最多 30 项，明确确认，限制为回收站顶层）。

**两个回收站都保留，互不混淆：**
- 默认「移入回收站」使用 CFM 自有本地回收站：`%LOCALAPPDATA%\CheemsFileManagerPC\RecycleBin`。恢复到原路径，如原路径已被占用则拒绝覆盖。
- 「Windows 回收站」打开系统回收站；「系统回收」将选中项送到 Windows 系统回收站（恢复由 Windows 资源管理器完成）。
- 跨盘 CFM 回收：先复制完成再移除源文件。大文件可能占用较多磁盘空间。
- 不会绕过 Windows 文件访问权限，拒绝处理目录联接和符号链接。

**发布注意：** GitHub Actions 能验证 .NET 构建、文件系统测试与 HTML/JS 模拟交互，不等于已在所有 Windows 10/11 真机验证。发布前请用可丢弃测试文件核对双栏多选、恢复、永久删除、系统回收站及 WebView2。EXE 未代码签名，可能触发 Windows 未知发布者提示，不要通过关闭杀毒软件来运行。

GitHub Actions 分支：`cfm-pc-web-v1.0-release`，产物名称：`CheemsFileManager-PC-V1.0-Full-win-x64`。
