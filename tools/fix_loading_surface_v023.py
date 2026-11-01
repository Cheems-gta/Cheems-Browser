#!/usr/bin/env python3
"""V0.2.3: keep a native loading/error surface over GeckoView until first paint.

Changes only the generated Android project. This follows the existing 0.2.2 patch.
The current browser had two white-screen paths:
  1. GeckoView is shown before the new page has painted content.
  2. A failed SearXNG JSON request automatically opens a public HTML endpoint.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv)>1 else "source/CheemsGeckoBrowser")
path = root/"app/src/main/java/io/cheems/browser/MainActivity.kt"
source = path.read_text(encoding="utf-8")

def change(before, after, key):
    global source
    count = source.count(before)
    if count != 1:
        raise RuntimeError(f"{key}: expected one source match, found {count}")
    source = source.replace(before, after, 1)

# Main display is now explicitly selected from three states:
# home, GeckoView with opaque native loading view, GeckoView with rendered content.
# GeckoView gets to stay attached and can render behind the native loading view.
change(
'''    private fun showContent(tab: BrowserTab) {
        homeView.visibility = if (tab.atHome) View.VISIBLE else View.GONE
        engineView.visibility = if (tab.atHome) View.GONE else View.VISIBLE
    }
''',
'''    private fun showContent(tab: BrowserTab) {
        val empty = tab.url.isBlank() || tab.url.equals("about:blank", ignoreCase = true)
        val home = tab.atHome || empty
        homeView.visibility = if (home) View.VISIBLE else View.GONE
        engineView.visibility = if (home) View.GONE else View.VISIBLE
        if (home) {
            clearFailure()
            if (::loading.isInitialized) loading.visibility = View.GONE
        }
    }
''', "blank session should stay on home")

change(
'''        val tab = currentTab()
        cancelPageTimer()
        clearFailure()
        tab.keepHome = true
        tab.atHome = true
        address.setText("")
''',
'''        val tab = currentTab()
        cancelPageTimer()
        clearFailure()
        tab.keepHome = true
        tab.atHome = true
        tab.failed = false
        tab.painted = false
        if (tab.url.equals("about:blank", ignoreCase = true)) tab.url = ""
        address.setText("")
''', "home should clear blank browser state")

change(
'''        address.setText(if (tab.atHome) "" else tab.url)
        showContent(tab)
''',
'''        address.setText(if (tab.atHome || tab.url.isBlank() ||
            tab.url.equals("about:blank", ignoreCase = true)) "" else tab.url)
        showContent(tab)
''', "tab switching should not expose about blank")

change(
'''        val tab = currentTab()
        tab.painted = false
        tab.failed = false
        val serial = pageSequence
''',
'''        val tab = currentTab()
        tab.painted = false
        tab.failed = false
        // A blank GeckoView surface is not a useful loading indicator. Cover
        // it with the native recovery panel until first contentful paint.
        if (::failureView.isInitialized && !tab.atHome) {
            failureTitle.text = "正在加载网页…"
            failureBody.text = "页面正在载入。若一直没有显示内容，可重试或返回主页。"
            failureView.visibility = View.VISIBLE
        }
        val serial = pageSequence
''', "show native surface while loading")

change(
'''        if (!tab.session.isOpen) {
            tab.session.open(runtime)
            if (!tab.atHome && tab.url.isNotBlank()) tab.session.loadUri(tab.url)
        }
        address.setText''',
'''        if (!tab.session.isOpen) {
            tab.session.open(runtime)
            if (!tab.atHome && tab.url.isNotBlank()) tab.session.loadUri(tab.url)
        }
        if (!tab.atHome && tab.url.isNotBlank() && !tab.painted) {
            // Switching to an unpainted tab should not reveal Gecko's white
            // surface. Existing page timer belongs to a different tab.
            startPageTimer(tab.session)
        } else if (tab.failed && !tab.atHome) {
            showFailure("网页加载失败", "这个标签页未能正常显示，请重试或回到主页。")
        }
        address.setText''', "tab switch loading state")

# The search Activity previously auto-loaded a SearXNG HTML page when a
# public JSON endpoint was blocked. That caused a visible white GeckoView
# surface, sometimes indefinitely. Instead remain on native results UI and
# offer explicit retry, instance settings, and opt-in HTML fallback.
search_path = Path("android/CheemsSearchActivity.kt")
search = search_path.read_text(encoding="utf-8")
old = '''            } catch (_: Exception) {
                // Many public instances disable JSON. Fall back to HTML in
                // our own GeckoView instead of scraping someone else's site.
                main.post {
                    if (isFinishing || isDestroyed || id != serial) return@post
                    returnBrowser(apiUrl(base, q, false))
                }
            }
'''
new = '''            } catch (_: Exception) {
                // A public SearXNG instance may disable JSON, rate-limit, or
                // be unreachable. Do not automatically send the user into a
                // broken HTML result page and leave GeckoView white.
                main.post {
                    if (isFinishing || isDestroyed || id != serial) return@post
                    progress.visibility = View.GONE
                    list.removeAllViews()
                    message.text = "Cheems Search · 搜索服务不可用"
                    list.addView(label("搜索服务暂时不可用", 20f).apply {
                        setPadding(0, dp(20), 0, dp(12))
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    list.addView(label(
                        "当前搜索源没有返回可用结果。可能是公共实例限流、关闭了 JSON 接口，或网络连接失败。可以更换搜索源后重试。",
                        15f, Color.rgb(195, 212, 231)).apply {
                        setPadding(0, 0, 0, dp(24))
                    })
                    val options = listOf(
                        "↻  重试搜索" to { search(q) },
                        "⚙  更换搜索源" to { settings() },
                        "↗  尝试打开网页搜索" to { returnBrowser(apiUrl(base, q, false)) }
                    )
                    options.forEach { (caption, action) ->
                        list.addView(label(caption, 16f, Color.rgb(183, 223, 255)).apply {
                            gravity = Gravity.CENTER
                            background = glass(Color.rgb(31, 43, 71), Color.rgb(75, 95, 133))
                            setOnClickListener { action() }
                        }, LinearLayout.LayoutParams(-1, dp(52)).apply {
                            bottomMargin = dp(12)
                        })
                    }
                }
            }
'''
if search.count(old) != 1:
    raise RuntimeError(f"Native search failure handler: found {search.count(old)} matches")
search_path.write_text(search.replace(old,new),encoding="utf-8")

path.write_text(source,encoding="utf-8")
version = root/"app/build.gradle.kts"
build=version.read_text(encoding="utf-8")
if build.count('versionCode = 4') != 1 or build.count('versionName = "0.2.2"') != 1:
    raise RuntimeError("Expected Cheems V0.2.2 project before V0.2.3 patch")
version.write_text(build.replace('versionCode = 4','versionCode = 5')
                   .replace('versionName = "0.2.2"', 'versionName = "0.2.3"'),
                   encoding="utf-8")
print("V0.2.3 native loading/error UI and search fallback patch applied")
