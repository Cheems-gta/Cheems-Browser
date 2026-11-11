# Cheems File Manager Android V0.4

Android 11+ dual-pane file manager. v0.4 is a visual refresh: blue-black gradient background, semi-transparent frosted-glass-style cards and panels (not true live blur), rounded rectangle icon holders, end ellipsis for long names and paths, and glass buttons.

## Folder navigation
Every pane begins with a **📁 ..** parent folder row, including on the root (non-navigable there). Tapping the row navigates up in that pane. Each opened folder also creates a compact tab in the top of its pane. Tap a tab to switch to it or tap that tab's **⋮** button to switch, close, or close other tabs. Tab lists max 12 per pane; the currently browsed folder is remembered across restarts but open tab history currently is session-only.

## Continued features from v0.3
- Top path with manually entered path, search button, options button; no bottom toolbar.
- Search current folder recursively, 35k scanned item cap and 120 results cap.
- Long-press file/folder for copy, rename, delete, text editing, and archive extraction when supported.
- Selected file highlight is a brighter blue translucent card.
- Archive extractor supports ZIP, 7z, RAR (Junrar), split archives and several niche formats. Rare and encrypted formats vary and some are not supported.
- Special Android MANAGE_EXTERNAL_STORAGE grant is requested once and generally remembered unless revoked/reinstalled. Not root, not Shizuku, does not grant access to protected app-private paths.

## Cautions
- Permanent delete does not use recycle bin.
- Archive extraction can leave partial output when interrupted, and ZIP/7z security quotas differ from RAR.
- No live window blur: translucent layered gradients + glow approximate frosted glass without requiring blur on older Android.
- GitHub CI compilation isn't equivalent to on-phone testing. Test only on sample files and trusted archives.
- GitHub debug builds may have changing signatures; if upgrading fails, uninstall old debug build (after backing up files/settings) and install new one.

Source branch: `cfm-android-v0.4`. Workflow: `.github/workflows/cfm-android-build.yml`.
