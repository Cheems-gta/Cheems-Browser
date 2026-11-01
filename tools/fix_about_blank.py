#!/usr/bin/env python3
"""Fix GeckoView's about:blank placeholder being shown as a white browser page.

Runs after tools/fix_white_screen.py on the unpacked Kotlin project.
Fails on unknown source layouts to avoid silent corruption.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "source/CheemsGeckoBrowser")
main = root / "app/src/main/java/io/cheems/browser/MainActivity.kt"
source = main.read_text(encoding="utf-8")

def patch(before, after, label):
    global source
    matches = source.count(before)
    if matches != 1:
        raise RuntimeError(f"{label}: expected one source anchor, found {matches}")
    source = source.replace(before, after, 1)

# GeckoView reports about:blank as a successful navigation for newly opened
# window.open sessions. It is NOT evidence that a useful page was rendered.
patch(
'''                if (!url.isNullOrBlank()) {
                    tab.url = url
                    if (!tab.keepHome) tab.atHome = false
                }
                if (isCurrent(tab) && !tab.keepHome) {
                    address.setText(tab.url)
                    showContent(tab)
                }
''',
'''                if (url.equals("about:blank", ignoreCase = true)) {
                    // Never expose the engine's empty placeholder as a white page.
                    // Keep the previously visited real URL, if any, for retry.
                    if (tab.url.equals("about:blank", ignoreCase = true)) tab.url = ""
                    if (tab.url.isBlank() || tab.atHome) {
                        tab.atHome = true
                        if (isCurrent(tab)) {
                            cancelPageTimer()
                            clearFailure()
                            address.setText("")
                            showContent(tab)
                            updateControls()
                            loading.visibility = View.GONE
                        }
                    } else if (isCurrent(tab) && !tab.keepHome) {
                        cancelPageTimer()
                        showFailure("打开了空白网页", "网页跳到了内部空白页，可重试或返回上一页。")
                    }
                    return
                }
                if (!url.isNullOrBlank()) {
                    tab.url = url
                    if (!tab.keepHome) tab.atHome = false
                }
                if (isCurrent(tab) && !tab.keepHome) {
                    address.setText(tab.url)
                    showContent(tab)
                }
''', "blank location guard")

# Avoid switching away from the page the user was actually reading for a
# transient blank popup. Gecko may still navigate the new background session
# to a real URL; the user can select it from tab picker.
patch(
'''                val newTab = newTab()
                return GeckoResult.fromValue(newTab.session)
''',
'''                val originalTabIndex = activeTab
                val opened = newTab()
                if (uri.isBlank() || uri.equals("about:blank", ignoreCase = true)) {
                    switchTo(originalTabIndex)
                }
                return GeckoResult.fromValue(opened.session)
''', "blank popup behavior")

patch(
'''            override fun onPageStart(session: GeckoSession, url: String) {
                if (isCurrentSession(session) && !currentTab().keepHome) {
                    startPageTimer(session)
                    loading.progress = 5
                    loading.visibility = View.VISIBLE
                }
            }
''',
'''            override fun onPageStart(session: GeckoSession, url: String) {
                if (url.equals("about:blank", ignoreCase = true)) return
                if (isCurrentSession(session) && !currentTab().keepHome) {
                    startPageTimer(session)
                    loading.progress = 5
                    loading.visibility = View.VISIBLE
                }
            }
''', "do not treat blank as page load")

patch(
'''                    val tab = currentTab()
                    tab.painted = true
''',
'''                    val tab = currentTab()
                    if (tab.atHome || tab.url.equals("about:blank", ignoreCase = true)) return
                    tab.painted = true
''', "do not mark blank home as successful first paint")

patch(
'''        val input = raw.trim()
        if (input.isBlank()) return
        val url = normalizeAddress(input)
''',
'''        val input = raw.trim()
        if (input.isBlank()) return
        // about:blank is a browser-internal placeholder, not a usable website.
        if (input.equals("about:blank", ignoreCase = true)) {
            showHome()
            return
        }
        val url = normalizeAddress(input)
''', "handle explicit blank address")

main.write_text(source, encoding="utf-8")

version = root / "app/build.gradle.kts"
build = version.read_text(encoding="utf-8")
if build.count('versionCode = 3') != 1 or build.count('versionName = "0.2.1"') != 1:
    raise RuntimeError("Unexpected version: expected V0.2.1 build")
version.write_text(build.replace('versionCode = 3', 'versionCode = 4')
                   .replace('versionName = "0.2.1"', 'versionName = "0.2.2"'),
                   encoding="utf-8")
print("Cheems Browser V0.2.2: blank popup/location guards installed")
