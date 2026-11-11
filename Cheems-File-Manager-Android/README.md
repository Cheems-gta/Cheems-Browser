# Cheems File Manager Android V0.5

Native Android 11+ dual-pane file manager. v0.5 adds optional Shizuku-based **read-only** directory listings for Android/data and Android/obb.

## Permissions
- Standard directories: Android `MANAGE_EXTERNAL_STORAGE`, granted once from system settings.
- Protected Android/data or Android/obb: the user must separately install/start Shizuku and explicitly approve CFM from Shizuku; permissions are not automatic. Choose top-right ⋮ > Shizuku 连接.
- Shizuku may run as shell (ADB) or root. This build uses only a narrow read-only UserService for those directory trees. It does NOT execute shell commands or access /data/user/0.
- System/OEM behavior varies. Even if the service connects, listing protected folders may still fail. The APK is NOT guaranteed to fix every Android 13–17 device.

## User interface
- Glass-themed UI and closable folder tabs, long-press operations and the archive formats from v0.4 remain.
- For Android/data and Android/obb: **read-only browsing** only. No edits, deletion, copy-out, search, or archive extraction in those protected directories yet.
- For ordinary folders: normal existing operations remain.
- Shizuku is OPTIONAL. If it isn't installed or running, normal file management works.

## Testing and safety
This is an experimental GitHub Actions debug APK. A passing build only verifies compilation, not successful Shizuku user-service binding on your phone. Shizuku's adb mode may need restarting after reboot. Do not force-install or disable Android security warnings.
