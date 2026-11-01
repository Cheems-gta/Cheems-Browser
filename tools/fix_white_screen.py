#!/usr/bin/env python3
"""Cheems Browser V0.2 -> V0.2.1: recover from failed/blank GeckoView loads."""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "source/CheemsGeckoBrowser")
path=root/"app/src/main/java/io/cheems/browser/MainActivity.kt"
text=path.read_text(encoding="utf-8")
def replace(old,new,name):
    global text
    if text.count(old)!=1:
        raise RuntimeError(f"{name}: expected 1 location, got {text.count(old)}")
    text=text.replace(old,new,1)

replace("import android.os.Bundle\n",
        "import android.os.Bundle\nimport android.os.Handler\nimport android.os.Looper\n", "handler imports")
replace("        var canForward: Boolean = false\n",
        "        var canForward: Boolean = false,\n        var keepHome: Boolean = false,\n        var painted: Boolean = false,\n        var failed: Boolean = false\n", "tab state")
replace("    private lateinit var tabButton: TextView\n",
        """    private lateinit var tabButton: TextView
    private lateinit var failureView: LinearLayout
    private lateinit var failureTitle: TextView
    private lateinit var failureBody: TextView
    private val pageHandler = Handler(Looper.getMainLooper())
    private var pageTimer: Runnable? = null
    private var pageSequence = 0
""", "failure state")
replace("""        homeView = makeHome()
        canvas.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
""","""        homeView = makeHome()
        canvas.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        failureView = buildFailureView()
        canvas.addView(failureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
""","failure overlay placement")
replace("    private fun createSession(): GeckoSession {", """    private fun buildFailureView(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(25), dp(25), dp(25), dp(25))
            setBackgroundColor(Color.rgb(11, 18, 37))
            visibility = View.GONE
        }
        failureTitle = label("网页加载失败", 23f).apply { typeface = Typeface.DEFAULT_BOLD }
        panel.addView(failureTitle, withMargins(-1, dp(66)))
        failureBody = label("网页暂时无法显示", 15f, Color.rgb(175, 193, 220))
        panel.addView(failureBody, withMargins(-1, -2, 0, 0, 0, 25))
        val retry = navButton("↻  重新加载").apply {
            textSize = 16f
            setOnClickListener {
                if (tabs.isEmpty()) return@setOnClickListener
                val tab = currentTab()
                if (tab.atHome || tab.url.isBlank()) { showHome(); return@setOnClickListener }
                tab.failed = false
                startPageTimer(tab.session)
                if (!tab.session.isOpen) tab.session.open(runtime)
                tab.session.loadUri(tab.url)
            }
        }
        panel.addView(retry, withMargins(-1, dp(50), 4, 0, 4, 12))
        val back = navButton("‹  返回上一页").apply {
            textSize = 16f
            setOnClickListener {
                if (tabs.isNotEmpty() && currentTab().canBack) goBack() else showHome()
            }
        }
        panel.addView(back, withMargins(-1, dp(50), 4, 0, 4, 12))
        val home = navButton("⌂  返回主页").apply {
            textSize = 16f
            setOnClickListener { showHome() }
        }
        panel.addView(home, withMargins(-1, dp(50), 4, 0, 4, 0))
        return panel
    }

    private fun cancelPageTimer() {
        pageSequence++
        pageTimer?.let { pageHandler.removeCallbacks(it) }
        pageTimer = null
    }
    private fun clearFailure() {
        if (::failureView.isInitialized) failureView.visibility = View.GONE
    }
    private fun showFailure(title: String, detail: String) {
        if (tabs.isEmpty() || currentTab().atHome || currentTab().keepHome ||
            isFinishing || isDestroyed) return
        failureTitle.text = title
        failureBody.text = detail
        failureView.visibility = View.VISIBLE
        loading.visibility = View.GONE
        currentTab().failed = true
    }
    private fun startPageTimer(session: GeckoSession) {
        if (!isCurrentSession(session)) return
        cancelPageTimer()
        clearFailure()
        val tab = currentTab()
        tab.painted = false
        tab.failed = false
        val serial = pageSequence
        val task = Runnable {
            if (serial == pageSequence && isCurrentSession(session) &&
                !currentTab().painted) {
                showFailure("网页显示超时",
                    "页面没有正常显示内容。可以重试、返回上一页，或回到主页。")
            }
        }
        pageTimer = task
        pageHandler.postDelayed(task, 20000L)
    }

    private fun rendererStopped(session: GeckoSession) {
        if (isCurrentSession(session)) {
            cancelPageTimer()
            showFailure("网页进程已停止",
                "网页渲染进程意外退出，点击重新加载可重新打开当前网页。")
        }
    }

    private fun createSession(): GeckoSession {""", "recovery methods")
replace("""                tab.title = title?.takeIf { it.isNotBlank() } ?: "网页"
            }
        }
""","""                tab.title = title?.takeIf { it.isNotBlank() } ?: "网页"
            }
            override fun onCrash(session: GeckoSession) { rendererStopped(session) }
            override fun onKill(session: GeckoSession) { rendererStopped(session) }
            override fun onFirstContentfulPaint(session: GeckoSession) {
                if (isCurrentSession(session)) {
                    val tab = currentTab()
                    tab.painted = true
                    if (!tab.failed) { cancelPageTimer(); clearFailure() }
                }
            }
        }
""","render process callbacks")
replace("        session.navigationDelegate = object : GeckoSession.NavigationDelegate {",
        """        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(
                session: GeckoSession, uri: String?,
                error: org.mozilla.geckoview.WebRequestError
            ): GeckoResult<String>? {
                if (isCurrentSession(session)) {
                    cancelPageTimer()
                    showFailure("网页无法打开", "网络连接或目标网站出现问题。请重试。")
                }
                return null
            }
""", "load error callback")
replace("""                    tab.atHome = false
                }
                if (isCurrent(tab)) {
                    address.setText(tab.url)
                    showContent(tab)
                }
""","""                    if (!tab.keepHome) tab.atHome = false
                }
                if (isCurrent(tab) && !tab.keepHome) {
                    address.setText(tab.url)
                    showContent(tab)
                }
""", "home late callbacks")
replace("""            override fun onPageStart(session: GeckoSession, url: String) {
                if (isCurrentSession(session)) {
                    loading.progress = 5
                    loading.visibility = View.VISIBLE
                }
            }
""","""            override fun onPageStart(session: GeckoSession, url: String) {
                if (isCurrentSession(session) && !currentTab().keepHome) {
                    startPageTimer(session)
                    loading.progress = 5
                    loading.visibility = View.VISIBLE
                }
            }
""","start load")
replace("""            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (isCurrentSession(session)) loading.visibility = View.GONE
            }
""","""            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!isCurrentSession(session)) return
                loading.visibility = View.GONE
                if (!success && !currentTab().keepHome) {
                    cancelPageTimer()
                    showFailure("网页加载失败", "该网页未能正常加载，可尝试重新加载。")
                } else if (currentTab().painted && !currentTab().failed) {
                    cancelPageTimer()
                    clearFailure()
                }
                // Successful loading without first paint remains under timeout watch.
            }
""","stop load")
replace("""        address.setText(if (tab.atHome) "" else tab.url)
        showContent(tab)
""","""        cancelPageTimer()
        clearFailure()
        if (!tab.session.isOpen) {
            tab.session.open(runtime)
            if (!tab.atHome && tab.url.isNotBlank()) tab.session.loadUri(tab.url)
        }
        address.setText(if (tab.atHome) "" else tab.url)
        showContent(tab)
""","switch tab")
replace("""    private fun showHome() {
        val tab = currentTab()
        tab.atHome = true
""","""    private fun showHome() {
        val tab = currentTab()
        cancelPageTimer()
        clearFailure()
        tab.keepHome = true
        tab.atHome = true
""","home")
replace("""        val tab = currentTab()
        tab.atHome = false
        tab.url = url
""","""        val tab = currentTab()
        tab.keepHome = false
        tab.atHome = false
        tab.url = url
""","navigate reset")
replace("""        showContent(tab)
        tab.session.loadUri(url)
        updateControls()
""","""        showContent(tab)
        startPageTimer(tab.session)
        if (!tab.session.isOpen) tab.session.open(runtime)
        tab.session.loadUri(url)
        updateControls()
""","navigate start")
replace("""                if (tab.atHome) return@setOnClickListener
                tab.session.reload()
""","""                if (tab.atHome) return@setOnClickListener
                startPageTimer(tab.session)
                if (!tab.session.isOpen) {
                    tab.session.open(runtime)
                    if (tab.url.isNotBlank()) tab.session.loadUri(tab.url)
                } else tab.session.reload()
""","reload")
replace("""    private fun goBack() {
        val tab = currentTab()
""","""    private fun goBack() {
        cancelPageTimer()
        clearFailure()
        val tab = currentTab()
""","back navigation")
replace("""        tabs.removeAt(activeTab)
        closed.session.close()
""","""        cancelPageTimer()
        clearFailure()
        tabs.removeAt(activeTab)
        closed.session.close()
""","close tab")
replace("""    override fun onDestroy() {
        engineView.releaseSession()
""","""    override fun onDestroy() {
        cancelPageTimer()
        engineView.releaseSession()
""","cleanup")
path.write_text(text, encoding="utf-8")
gradle=root/"app/build.gradle.kts"
build=gradle.read_text(encoding="utf-8")
assert 'versionCode = 2' in build and 'versionName = "0.2.0"' in build
gradle.write_text(build.replace('versionCode = 2','versionCode = 3')
    .replace('versionName = "0.2.0"','versionName = "0.2.1"'),encoding="utf-8")
print("Cheems white-screen recovery V0.2.1 applied")
