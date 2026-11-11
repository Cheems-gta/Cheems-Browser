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
