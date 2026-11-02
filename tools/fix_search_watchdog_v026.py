#!/usr/bin/env python3
"""Cheems Search 0.2.6: never leave users stuck waiting on a public SearXNG API.

Runs AFTER build v0.2.5 integration and intentionally does not change the
GeckoView engine or claim a public instance can return JSON when it cannot.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "source/CheemsGeckoBrowser")
activity=Path("android/CheemsSearchActivity.kt")
s=activity.read_text(encoding="utf-8")

def apply(old,new,label):
    global s
    if s.count(old)!=1:
        raise RuntimeError(f"{label}: expected 1 anchor, got {s.count(old)}")
    s=s.replace(old,new,1)

apply(
'''    private fun apiUrl(base: String, q: String, json: Boolean): String =''',
'''    private fun addSearchOption(caption: String, action: () -> Unit) {
        list.addView(label(caption, 16f, Color.rgb(182, 218, 255)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = glass(Color.rgb(31, 43, 71), Color.rgb(75, 95, 133))
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(60)).apply {
            setMargins(0, dp(7), 0, dp(7))
        })
    }

    private fun fallbackGoogle(q: String) {
        // Explicitly disclose that this is a third-party service.
        returnBrowser("https://www.google.com/search?hl=zh-CN&q=" + Uri.encode(q))
    }

    private fun displayNoResults(q: String, requestId: Int, note: String) {
        if (isFinishing || isDestroyed || requestId != serial) return
        progress.visibility = View.GONE
        list.removeAllViews()
        list.addView(label("搜索服务没有响应", 21f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(12))
        })
        list.addView(label(note, 15f, Color.rgb(178, 197, 220)).apply {
            setPadding(0, 0, 0, dp(20))
        })
        addSearchOption("↗  使用 Google 备用搜索（第三方）") { fallbackGoogle(q) }
        addSearchOption("↻  重新搜索") { search(q) }
        addSearchOption("⚙  切换 SearXNG 搜索服务器") { settings() }
        message.text = "Cheems Search · 当前公共搜索接口不可用"
    }

    private fun apiUrl(base: String, q: String, json: Boolean): String =''',
    "native search fallback actions")

apply(
'''        list.addView(label("正在搜索：" + q, 16f).apply {
            setPadding(0, dp(22), 0, dp(20))
        })
        progress.visibility = View.VISIBLE
''',
'''        list.addView(label("正在搜索：" + q, 16f).apply {
            setPadding(0, dp(22), 0, dp(20))
        })
        // Don't hide every useful action behind a slow public API.
        addSearchOption("↗  立即使用 Google 备用搜索（第三方）") {
            fallbackGoogle(q)
        }
        progress.visibility = View.VISIBLE
        // DNS lookup and captive portals may ignore HttpURLConnection timeouts.
        // Keep the native UI responsive even when a worker never resolves.
        main.postDelayed({
            if (id == serial && !isFinishing && !isDestroyed &&
                progress.visibility == View.VISIBLE) {
                displayNoResults(
                    q, id,
                    "公共 SearXNG 服务器响应过慢或拒绝查询。" +
                    " 你可以换自己的 SearXNG 服务器，或者使用明确标注的备用搜索。"
                )
            }
        }, 4000L)
''',
    "watchdog and immediate alternative")

# A pending request may never finish (e.g. stalled DNS), and a single-thread
# worker would prevent any subsequent search from starting. Use a small pool.
apply(
'''    private val worker = Executors.newSingleThreadExecutor()''',
'''    private val worker = Executors.newFixedThreadPool(3)''',
    "avoid queued blocked searches")

# Remove automated third-party jump: user explicitly said they want to build
# an independent Cheems Search UI. A button leaves search-provider choice clear.
apply(
'''                        android.widget.Toast.makeText(
                            this, "Cheems Search 暂不可用，改用 Google 备用搜索",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        returnBrowser(
                            "https://www.google.com/search?hl=zh-CN&q=" + Uri.encode(q)
                        )
                        return@post
''',
'''                        displayNoResults(
                            q, id,
                            "公共 SearXNG 返回错误或拒绝了请求；" +
                            "当前没有可用的 Cheems 原生搜索结果。请选择备用方式。"
                        )
                        return@post
''',
    "don't automatically redirect after backend failure")

activity.write_text(s,encoding="utf-8")
# Build step previously copied this file BEFORE V0.2.3/V0.2.5 patches!
# Synchronize final patched search code into the Android project, otherwise
# CI compiles an obsolete CheemsSearchActivity that still has old 21s waits.
installed=root/"app/src/main/java/io/cheems/browser/CheemsSearchActivity.kt"
installed.write_text(s,encoding="utf-8")

gradle=root/"app/build.gradle.kts"
g=gradle.read_text(encoding="utf-8")
if g.count('versionCode = 7')!=1 or g.count('versionName = "0.2.5"')!=1:
    raise RuntimeError("Expected v0.2.5 generated app")
gradle.write_text(g.replace('versionCode = 7','versionCode = 8')
                  .replace('versionName = "0.2.5"','versionName = "0.2.6"'),encoding="utf-8")
print("Cheems Search v0.2.6: native 4s watchdog and explicit backup actions.")
