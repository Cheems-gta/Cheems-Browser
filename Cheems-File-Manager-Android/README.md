# Cheems File Manager Android V0.3

A native Android 11+ file manager featuring an MT-inspired dual pane file explorer. Uses Android's official one-time `MANAGE_EXTERNAL_STORAGE` permission; no root or Shizuku required.

## Changes since V0.2
- Remove bottom toolbar completely.
- Fix Android 15/16/17 status bar overlap using `WindowInsets` and reduce path font size.
- Top toolbar: current full path, **search** button, options menu.
- Search current pane recursively (bounded to 35,000 scanned entries, first 120 matches).
- Long-press file/folder for a context menu: copy, rename, delete (permanent + confirmation), text edit, **extract** compatible archives.
- More menu at top for new folder, up, refresh, permission, supported formats.
- Archive extraction uses Apache Commons Compress 1.28.0, XZ for Java 1.10, Junrar 8.1.2.

## Supported extraction (subject to format / algorithm)
- ZIP, split ZIP (.z01/.zip), JAR, APK, XAPK, APKS, APKM
- 7z and 7z.001 / 7z.002 sequences (joined to temporary archive for extraction)
- RAR including common multivolume formats via Junrar (depends on archive)
- TAR, TGZ/TAR.GZ, TBZ2/TAR.BZ2, TXZ/TAR.XZ, TAR.LZMA, TAR.LZ4
- GZ, BZ2, XZ, LZMA, LZ4, legacy Unix Z
- AR, DEB, CPIO, ARJ, DUMP

NOT supported: CAB, ISO, WIM, DMG, ACE, arbitrary proprietary types, ZIP spanned across removable volumes. Encrypted archives and uncommon compression methods may not work. The user request for 'every niche format' is not fully achievable with these open-source libraries.

## Extraction safety
- For ZIP/7z/TAR/etc., reject path traversal, skip link entries where possible, cap entries at 4k, per-file at 512 MB, total output at 1 GiB. Large/odd archives may exceed these limits and fail. Partial extraction directories may remain after failure.
- RAR extraction uses Junrar's own extraction security; this app's global output limits do **not** apply to RAR. Avoid unknown untrusted RAR archives.
- Split 7z.001/ZIP.001 combines up to 100 contiguous parts into temporary disk space (<=2 GB). All volumes must be in the same folder, starting with .001. Incomplete volumes may fail.
- **Delete is permanent**, does not use a recycle bin. A separate confirmation is required before recursive deletion. Back up important files.
- Search/copy/extract/delete run on a worker thread. File browsing screens are built on the main thread and folders with 400+ entries display only the first 400.

## Testing
- GitHub Actions build successful != on-device fully tested.
- Debug signing key can change between CI builds, so you may need to uninstall older versions before installing this one. Do not disable Android's protections to install it.
- Permission generally persists, but uninstalling or revoking it can reset it.
- Android/data, app private storage and other protected directories are still inaccessible with MANAGE_EXTERNAL_STORAGE.
- Shizuku support remains **not implemented** in V0.3.
