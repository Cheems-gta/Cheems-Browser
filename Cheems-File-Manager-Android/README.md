# Cheems File Manager Android V0.2
Android 11+ file manager, with an MT-inspired dark dual-pane list and full path navigation.

## New in 0.2
- Shows files immediately at `/storage/emulated/0/` after one-time Android system "Manage all files" authorization.
- Stores file paths across app restarts (and permission normally stays granted until revoked).
- Top full current path (tap to enter a path); each pane has an independent current directory.
- Portrait/landscape dual side-by-side pane, folder + file names and modified time, storage usage.
- Tap folder to navigate; tap file to select; long-press folder to select it.
- Copy selection to the other pane, create folder, rename, edit common UTF-8 text files (<=256 KB).
- Non-root, offline, no ads, no INTERNET permission.
- File copy runs in background on a single worker. Failed copies may leave incomplete targets.
- Delete intentionally not included to limit accidental loss in initial testing.

## Permissions
Uses the standard Android MANAGE_EXTERNAL_STORAGE special access panel. Open, tap authorization button, enable it once and return. Access is then remembered by Android. This does NOT grant access to Android/data or other protected app private directories.
Shizuku is not required or integrated in v0.2. Optional Shizuku support may follow, but Shizuku authorization is not equivalent to granting broad private app data access and may require a running Shizuku service.

## Install note
GitHub Actions builds a **debug-signed** APK; this is an experimental build.
Debug APK signature may differ from 0.1's build because CI creates fresh signing keys; if the phone says 'app not installed', uninstall the old test app before installing v0.2. Export test files first; uninstall clears app preferences. Never disable system security protections to install.

## CI build
GitHub branch `cfm-android-v0.2`, workflow `.github/workflows/cfm-android-build.yml`. Download the artifact `CheemsFM-Android-V0.2-debug-apk` after a successful run.
