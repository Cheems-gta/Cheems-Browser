#!/usr/bin/env python3
"""V0.2.5: make searching a distinct action, add fallback and domain error search.

Compiled on top of V0.2.4 via Android CI.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "source/CheemsGeckoBrowser")
p=root/"app/src/main/java/io/cheems/browser/MainActivity.kt"
s=p.read_text(encoding="utf-8")
def edit(old,new,label):
    global s
    if s.count(old)!=1:raise RuntimeError(f"{label}: {s.count(old)} source anchors, expected 1")
    s=s.replace(old,new,1)

# A distinct, visible Search button avoids treating topics such as ROG.com
# as domain navigation when the user explicitly wants search results.
edit(
'''        toolbar.addView(go, withMargins(dp(46), dp(46), 8, 0, 0, 0))

        loading = ProgressBar''',
'''        toolbar.addView(go, withMargins(dp(46), dp(46), 8, 0, 0, 0))
        val searchAction = navButton("⌕").apply {
            contentDescription = "搜索关键词"
            textSize = 27f
            setOnClickListener { openSearch(address.text.toString()) }
        }
        toolbar.addView(searchAction, withMargins(dp(42), dp(46), 6, 0, 0, 0))

        loading = ProgressBar''',"dedicated search action")

edit(
'''    private fun navigate(raw: String) {''',
'''    private fun openSearch(text: String) {
        val query = text.trim()
        if (query.isBlank()) return
        address.clearFocus()
        hideKeyboard()
        startActivityForResult(
            Intent(this, CheemsSearchActivity::class.java).putExtra("query", query), 729
        )
    }

    private fun navigate(raw: String) {''',"single native search entrypoint")

# The direct navigation path already detects keyword input, but use the same
# native entrypoint rather than duplicating two nearly-identical activity launches.

# If a direct host cannot be reached, the failure panel offers a clear
# "search this site" action instead of repeatedly reloading a dead host.
edit(
'''        panel.addView(home, withMargins(-1, dp(50), 4, 0, 4, 0))
        return panel
''',
'''        panel.addView(home, withMargins(-1, dp(50), 4, 0, 4, 12))
        val lookup = navButton("⌕  搜索这个名称").apply {
            textSize = 16f
            setOnClickListener {
                if (tabs.isEmpty()) return@setOnClickListener
                val host = Uri.parse(currentTab().url).host
                val term = host?.removePrefix("www.")?.substringBeforeLast(".")
                    ?.takeIf { it.isNotBlank() } ?: currentTab().url
                openSearch(term)
            }
        }
        panel.addView(lookup, withMargins(-1, dp(50), 4, 0, 4, 0))
        return panel
''',"domain error search option")

p.write_text(s,encoding="utf-8")

q=Path("android/CheemsSearchActivity.kt")
src=q.read_text(encoding="utf-8")
def sed(old,new,label):
    global src
    if src.count(old)!=1:raise RuntimeError(f"{label}: {src.count(old)} anchors, expected 1")
    src=src.replace(old,new,1)

# We verified through GitHub Actions that priv.au gives HTTP 429 and two
# other public instances return HTML for format=json. Never keep opening
# blocked SearXNG HTML as though it were a functioning backend.
sed(
'''                main.post {
                    if (isFinishing || isDestroyed || id != serial) return@post
                    progress.visibility = View.GONE
                    list.removeAllViews()
''',
'''                main.post {
                    if (isFinishing || isDestroyed || id != serial) return@post
                    // Default public SearXNG frequently refuses JSON requests.
                    // Fall back to a real search webpage instead of cycling
                    // through endless empty native/GeckoView pages. This is
                    // explicitly labelled as a third-party backup.
                    if (base.trimEnd('/') == DEFAULT_INSTANCE.trimEnd('/')) {
                        android.widget.Toast.makeText(
                            this, "Cheems Search 暂不可用，改用 Google 备用搜索",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        returnBrowser(
                            "https://www.google.com/search?hl=zh-CN&q=" + Uri.encode(q)
                        )
                        return@post
                    }
                    progress.visibility = View.GONE
                    list.removeAllViews()
''',"explicit third-party fallback for failed public JSON")

sed(
'''                        "⚙  更换搜索源" to { settings() }
''',
'''                        "⚙  更换搜索源" to { settings() },
                        "↗  Google 备用搜索（第三方）" to {
                            returnBrowser("https://www.google.com/search?hl=zh-CN&q=" + Uri.encode(q))
                        }
''',"custom endpoint backup button")

sed("connection.connectTimeout = 4500","connection.connectTimeout = 2500","fast connect timeout")
sed("connection.readTimeout = 6000","connection.readTimeout = 4000","fast read timeout")
sed(
'''            .setMessage("填入你的 SearXNG HTTPS 地址。开启 JSON 可显示 Cheems 原生结果；公共实例若禁用 JSON，会自动切换到 GeckoView 网页结果。")
''',
'''            .setMessage("填入自己的 SearXNG HTTPS 地址并开启 JSON，即可使用 Cheems 原生结果页。默认公共实例失效时会改用 Google（第三方）备用搜索；自定义实例失败时保留错误提示与选择。")
''',"truthful settings description")
q.write_text(src,encoding="utf-8")

build=root/"app/build.gradle.kts"
g=build.read_text(encoding="utf-8")
if g.count('versionCode = 6')!=1 or g.count('versionName = "0.2.4"')!=1:
    raise RuntimeError("Expected 0.2.4 project")
build.write_text(g.replace('versionCode = 6','versionCode = 7')
                  .replace('versionName = "0.2.4"','versionName = "0.2.5"'),encoding="utf-8")
print("V0.2.5: explicit search action, site name lookup, safe Google fallback on SearXNG failure.")
