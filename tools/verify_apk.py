#!/usr/bin/env python3
"""Ensure the delivered file is a real ARM64 GeckoView APK, not an old artifact."""
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    names = apk.namelist()
    assert 'AndroidManifest.xml' in names
    assert 'classes.dex' in names
    libraries = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert libraries, 'APK has no native browser engine'
    assert {n.split('/')[1] for n in libraries} == {'arm64-v8a'}, libraries
    assert any(n.endswith('/libxul.so') for n in libraries), 'Gecko missing'
print('Verified: Android APK, DEX bytecode and ARM64 Gecko engine present.')
