#!/usr/bin/env python3
"""Cheems Browser 0.2.4: remove blocking loading page; keep SearXNG URLs native."""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv)>1 else "source/CheemsGeckoBrowser")
main = root/"app/src/main/java/io/cheems/browser/MainActivity.kt"
src = main.read_text(encoding="utf-8")

def patch(before,after,what):
    global src
    n=src.count(before)
    if n!=1: raise RuntimeError(f"{what}: expected 1 source anchor, got {n}")
    src=src.replace(before,after,1)

# Old loading implementation covered the browser in a full-screen error panel
# on EVERY navigation, even on an otherwise healthy connection.
patch(
'''        if (::failureView.isInitialized && !tab.atHome) {
            failureTitle.text = "正在加载网页…"
            failureBody.text = "页面正在载入。若一直没有显示内容，可重试或返回主页。"
            failureView.visibility = View.VISIBLE
        }
''',
'''        // No full-screen loading dialogue. Keep the real browser HOME visible
        // over an attached GeckoView while a page has not painted anything.
        // The thin progress bar under the address bar remains active.
        if (::failureView.isInitialized) failureView.visibility = View.GONE
        if (::homeView.isInitialized && !tab.atHome) homeView.visibility = View.VISIBLE
''', "remove blocking loading dialog")

patch(
'''        homeView.visibility = if (home) View.VISIBLE else View.GONE
        engineView.visibility = if (home) View.GONE else View.VISIBLE
''',
'''        // An unpainted GeckoView is white. Keep the familiar home screen
        // visible until GeckoView actually paints usable content.
        homeView.visibility = if (home || (!tab.painted && !tab.failed)) View.VISIBLE else View.GONE
        engineView.visibility = if (home) View.GONE else View.VISIBLE
''', "keep home over unpainted renderer")

patch(
'''                    tab.painted = true
                    if (!tab.failed) { cancelPageTimer(); clearFailure() }
''',
'''                    tab.painted = true
                    if (!tab.failed) {
                        cancelPageTimer()
                        clearFailure()
                        showContent(tab)
                    }
''', "expose renderer on first paint")

patch('pageHandler.postDelayed(task, 20000L)',
      'pageHandler.postDelayed(task, 10000L)',
      "shorten stuck navigation fallback to 10 seconds")

# The native recovery page should reload a real webpage, but route a SearXNG
# *search-result* URL back to our native search instead of a broken HTML page.
patch(
'''                if (tab.atHome || tab.url.isBlank()) { showHome(); return@setOnClickListener }
                tab.failed = false
''',
'''                if (tab.atHome || tab.url.isBlank()) { showHome(); return@setOnClickListener }
                if (tab.url.startsWith(SEARCH_ENDPOINT)) {
                    navigate(tab.url)
                    return@setOnClickListener
                }
                tab.failed = false
''', "search reload from recovery panel")

patch(
'''                if (tab.atHome) return@setOnClickListener
                startPageTimer(tab.session)
''',
'''                if (tab.atHome) return@setOnClickListener
                if (tab.url.startsWith(SEARCH_ENDPOINT)) {
                    navigate(tab.url)
                    return@setOnClickListener
                }
                startPageTimer(tab.session)
''', "search reload from toolbar")

# When an old tab or a pasted link contains a SearXNG URL, always open our
# native Cheems Search Activity; do not render the public instance HTML.
patch(
'''        val url = normalizeAddress(input)
''',
'''        // Search results never need to open a third-party HTML page.
        // Route pasted/reloaded SearXNG search URLs into native Cheems Search.
        val searchServer = getSharedPreferences("cheems_search", Context.MODE_PRIVATE)
            .getString("instance_url", "https://priv.au") ?: "https://priv.au"
        val explicit = Uri.parse(input)
        if (explicit.scheme.equals("https", true) &&
            explicit.host.equals(Uri.parse(searchServer).host, true) &&
            explicit.path == "/search") {
            val originalQuery = explicit.getQueryParameter("q")
            if (!originalQuery.isNullOrBlank()) {
                startActivityForResult(
                    Intent(this, CheemsSearchActivity::class.java)
                        .putExtra("query", originalQuery), 729)
                return
            }
        }
        val url = normalizeAddress(input)
''', "route explicit SearXNG URLs to native results")

main.write_text(src, encoding="utf-8")

searchFile = Path("android/CheemsSearchActivity.kt")
search = searchFile.read_text(encoding="utf-8")
bad = '''                        "↗  尝试打开网页搜索" to { returnBrowser(apiUrl(base, q, false)) }
'''
if search.count(bad) != 1: raise RuntimeError("Missing optional HTML fallback button")
# Remove the optional HTML fallback which could return to an endless white
# public search site. Keep native Retry and Change Search Source actions.
search=search.replace(
'''                        "⚙  更换搜索源" to { settings() },
                        "↗  尝试打开网页搜索" to { returnBrowser(apiUrl(base, q, false)) }
''',
'''                        "⚙  更换搜索源" to { settings() }
''', 1)
if "↗  尝试打开网页搜索" in search: raise RuntimeError("HTML fallback still present")
search=search.replace('connection.connectTimeout = 9000',
                      'connection.connectTimeout = 4500')
search=search.replace('connection.readTimeout = 12000',
                      'connection.readTimeout = 6000')
searchFile.write_text(search, encoding="utf-8")

version = root/"app/build.gradle.kts"
build=version.read_text(encoding="utf-8")
if build.count('versionCode = 5') != 1 or build.count('versionName = "0.2.3"') != 1:
    raise RuntimeError("Expected 0.2.3 project before fast-load patch")
version.write_text(build.replace('versionCode = 5', 'versionCode = 6')
                   .replace('versionName = "0.2.3"', 'versionName = "0.2.4"'),
                   encoding="utf-8")
print("Cheems Browser V0.2.4 fast native search and no blocking loader installed")
