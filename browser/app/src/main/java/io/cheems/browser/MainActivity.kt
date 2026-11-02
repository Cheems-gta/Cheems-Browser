package io.cheems.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.WindowInsets
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.min

/**
 * Cheems Browser V0.2.7
 * 原生 Kotlin 界面 + Firefox GeckoView 引擎，不使用 Android WebView。
 * V0.1 仅用于初步试用：暂不支持文件上传、相机/麦克风授权、下载及数据恢复。
 */
class MainActivity : Activity() {

    companion object {
        // GeckoRuntime 每个进程只创建一次。
        private var sharedRuntime: GeckoRuntime? = null
    }

    private data class BrowserTab(
        val session: GeckoSession,
        var url: String = "",
        var title: String = "新标签页",
        var atHome: Boolean = true,
        var canBack: Boolean = false,
        var canForward: Boolean = false,
        var keepHome: Boolean = false,
        var painted: Boolean = false,
        var failed: Boolean = false,
        var pendingPopup: Boolean = false
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTab = 0

    private lateinit var engineView: GeckoView
    private lateinit var homeView: View
    private lateinit var providerButton: TextView
    private lateinit var address: EditText
    private lateinit var loading: ProgressBar
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var tabButton: TextView
    private lateinit var failureView: LinearLayout
    private lateinit var failureTitle: TextView
    private lateinit var failureBody: TextView
    private val pageHandler = Handler(Looper.getMainLooper())
    private var pageTimer: Runnable? = null
    private var pageSequence = 0

    private val runtime: GeckoRuntime
        get() {
            if (sharedRuntime == null) {
                sharedRuntime = GeckoRuntime.create(applicationContext)
            }
            return sharedRuntime!!
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 13, 28)
        window.navigationBarColor = Color.rgb(8, 13, 28)
        buildInterface()
        newTab()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radius: Int = 17, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun label(text: String, size: Float = 17f, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    private fun navButton(text: String): TextView = label(text, 20f).apply {
        background = rounded(Color.argb(75, 255, 255, 255), 15,
            Color.argb(36, 255, 255, 255))
        isClickable = true
        isFocusable = true
    }

    private fun withMargins(
        width: Int, height: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 28))
        }
        setContentView(root)
        // Android 15+ 的强制 edge-to-edge：让地址栏与导航按钮避开系统状态栏。
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }

        // 顶部地址栏：可以输入网址，也可以直接搜索关键词。
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(8))
        }
        root.addView(toolbar, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        val brand = navButton("🦊").apply {
            contentDescription = "回到主页"
            setOnClickListener { showHome() }
        }
        toolbar.addView(brand, withMargins(dp(46), dp(46), 0, 0, 8, 0))

        address = EditText(this).apply {
            contentDescription = "地址栏"
            hint = "搜索，或输入网址"
            setHintTextColor(Color.rgb(157, 166, 186))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(Color.rgb(30, 38, 60), 16,
                Color.rgb(75, 85, 111))
            setPadding(dp(14), 0, dp(12), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f))

        val go = navButton("➜").apply {
            contentDescription = "访问或搜索"
            setOnClickListener { navigate(address.text.toString()) }
        }
        toolbar.addView(go, withMargins(dp(46), dp(46), 8, 0, 0, 0))
        val searchAction = navButton("⌕").apply {
            contentDescription = "搜索关键词"
            textSize = 27f
            setOnClickListener { openSearch(address.text.toString()) }
        }
        toolbar.addView(searchAction, withMargins(dp(42), dp(46), 6, 0, 0, 0))

        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(Color.rgb(123, 196, 255))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(8, 13, 28))
        }
        root.addView(loading, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val canvas = FrameLayout(this)
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        engineView = GeckoView(this).apply {
            tag = "browser_content"
            setBackgroundColor(Color.rgb(11, 18, 37))
        }
        canvas.addView(engineView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        homeView = makeHome().apply { tag = "browser_home" }
        canvas.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        failureView = buildFailureView().apply { tag = "browser_failure" }
        canvas.addView(failureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(bottom, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))

        backButton = navButton("‹").apply {
            contentDescription = "后退"
            textSize = 30f
            setOnClickListener { goBack() }
        }
        forwardButton = navButton("›").apply {
            contentDescription = "前进"
            textSize = 30f
            setOnClickListener {
                val tab = currentTab()
                if (!tab.atHome && tab.canForward) {
                    tab.keepHome = false
                    tab.session.goForward()
                }
            }
        }
        val home = navButton("⌂").apply {
            textSize = 25f
            contentDescription = "主页"
            setOnClickListener { showHome() }
        }
        val reload = navButton("↻").apply {
            contentDescription = "刷新页面"
            textSize = 26f
            setOnClickListener {
                val tab = currentTab()
                if (tab.atHome) return@setOnClickListener
                startPageTimer(tab.session)
                if (!tab.session.isOpen) {
                    tab.session.open(runtime)
                    if (tab.url.isNotBlank()) tab.session.loadUri(tab.url)
                } else tab.session.reload()
            }
        }
        tabButton = navButton("1").apply {
            textSize = 17f
            contentDescription = "管理标签页"
            setOnClickListener { showTabPicker() }
        }
        listOf(backButton, forwardButton, home, reload, tabButton).forEach { button ->
            bottom.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
    }

    private fun makeHome(): View {
        val backdrop = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(19, 37, 78), Color.rgb(12, 18, 40), Color.rgb(32, 24, 64))
        )
        return ScrollView(this).apply {
            background = backdrop
            isFillViewport = true
            val center = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22), dp(60), dp(22), dp(28))
            }
            addView(center)

            center.addView(label("✦  ·  ✧  ·  ✦", 20f, Color.rgb(163, 186, 255)))
            center.addView(label("CHEEMS", 37f).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
                letterSpacing = 0.13f
            }, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), 0, 16))
            center.addView(label("BROWSER  /  GECKO ENGINE", 11f,
                Color.rgb(160, 185, 231)))
            center.addView(label("一个真正使用 Firefox 内核的 Android 浏览器", 14f,
                Color.rgb(190, 205, 228)), withMargins(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68), 0, 15))

            val homeSearch = EditText(this@MainActivity).apply {
                hint = "搜索网页或输入网址…"
                setHintTextColor(Color.rgb(160, 175, 196))
                setTextColor(Color.WHITE)
                textSize = 16f
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = rounded(Color.argb(85, 255, 255, 255), 22,
                    Color.argb(90, 255, 255, 255))
                setPadding(dp(17), 0, dp(17), 0)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        navigate(text.toString())
                        setText("")
                        true
                    } else false
                }
            }
            center.addView(homeSearch, withMargins(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(59), 0, 8))

            providerButton = navButton("搜索：${selectedProvider().label}  ▾").apply {
                contentDescription = "选择搜索引擎"
                textSize = 15f
                setOnClickListener { chooseSearchProvider() }
            }
            center.addView(providerButton, withMargins(-1, dp(48), 0, 12))
            center.addView(label("快捷入口", 14f,
                Color.rgb(164, 191, 238)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 3, 16))

            val links = listOf(
                "Mozilla" to "https://www.mozilla.org/",
                "维基百科" to "https://www.wikipedia.org/",
                "GitHub" to "https://github.com/",
                "MDN 文档" to "https://developer.mozilla.org/"
            )
            links.chunked(2).forEach { rowLinks ->
                val row = LinearLayout(this@MainActivity)
                center.addView(row, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
                rowLinks.forEach { (name, url) ->
                    val tile = label(name, 15f).apply {
                        background = rounded(Color.argb(56, 255, 255, 255), 18,
                            Color.argb(70, 255, 255, 255))
                        setOnClickListener { navigate(url) }
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        setMargins(dp(5), dp(4), dp(5), dp(4))
                    })
                }
            }
            center.addView(label("CHEEMS  ·  V0.2.7", 12f,
                Color.rgb(136, 157, 200)), withMargins(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74), 0, 20))
        }
    }

    private fun buildFailureView(): LinearLayout {
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
        panel.addView(home, withMargins(-1, dp(50), 4, 0, 4, 12))
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
        // Keep GeckoView attached and visible during navigation. Re-showing the
        // HOME overlay on every redirect made searches appear to loop to HOME.
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

    private fun createSession(): GeckoSession {
        val session = GeckoSession()
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val tab = findTab(session) ?: return
                tab.title = title?.takeIf { it.isNotBlank() } ?: "网页"
            }
            override fun onCrash(session: GeckoSession) { rendererStopped(session) }
            override fun onKill(session: GeckoSession) { rendererStopped(session) }
            override fun onFirstContentfulPaint(session: GeckoSession) {
                val tab = findTab(session) ?: return
                tab.painted = true
                // A slow page can still recover after the loading watchdog.
                tab.failed = false
                if (isCurrent(tab) && !tab.keepHome && !tab.atHome) {
                    cancelPageTimer()
                    clearFailure()
                    showContent(tab)
                }
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(
                session: GeckoSession, uri: String?,
                error: org.mozilla.geckoview.WebRequestError
            ): GeckoResult<String>? {
                val tab = findTab(session) ?: return null
                if (!tab.keepHome) tab.failed = true
                if (isCurrent(tab) && !tab.keepHome) {
                    cancelPageTimer()
                    showFailure("网页无法打开", "网络连接或目标网站出现问题。请重试。")
                }
                return null
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val tab = findTab(session) ?: return
                if (url.equals("about:blank", ignoreCase = true)) {
                    if (tab.pendingPopup) return
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
                    if (tab.pendingPopup) {
                        tab.pendingPopup = false
                        switchTo(tabs.indexOf(tab))
                    }
                    if (!tab.keepHome) tab.atHome = false
                }
                if (isCurrent(tab) && !tab.keepHome) {
                    address.setText(tab.url)
                    showContent(tab)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                findTab(session)?.let {
                    it.canBack = canGoBack
                    if (isCurrent(it)) updateControls()
                }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                findTab(session)?.let {
                    it.canForward = canGoForward
                    if (isCurrent(it)) updateControls()
                }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Gecko opens the returned session itself. Calling newTab()
                // here used to open it prematurely and break target=_blank.
                val opened = BrowserTab(createSession(), url = uri,
                    atHome = false, pendingPopup = true)
                tabs.add(opened)
                updateControls()
                return GeckoResult.fromValue(opened.session)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (url.equals("about:blank", ignoreCase = true)) return
                val tab = findTab(session) ?: return
                if (tab.keepHome) return
                tab.url = url
                tab.atHome = false
                tab.painted = false
                tab.failed = false
                if (tab.pendingPopup) {
                    tab.pendingPopup = false
                    switchTo(tabs.indexOf(tab))
                }
                if (isCurrent(tab)) {
                    startPageTimer(session)
                    showContent(tab)
                    loading.progress = 5
                    loading.visibility = View.VISIBLE
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (isCurrentSession(session) && !currentTab().keepHome) {
                    loading.progress = progress
                    loading.visibility = if (progress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
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
        }
        return session
    }

    private fun newTab(): BrowserTab {
        val tab = BrowserTab(createSession())
        tabs.add(tab)
        tab.session.open(runtime)
        switchTo(tabs.lastIndex)
        return tab
    }

    private fun currentTab(): BrowserTab = tabs[activeTab]
    private fun findTab(session: GeckoSession): BrowserTab? = tabs.firstOrNull { it.session === session }
    private fun isCurrent(tab: BrowserTab): Boolean = tabs.getOrNull(activeTab) === tab
    private fun isCurrentSession(session: GeckoSession): Boolean =
        tabs.getOrNull(activeTab)?.session === session

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.isNotEmpty() && activeTab in tabs.indices) {
            tabs[activeTab].session.setActive(false)
        }
        activeTab = index
        val tab = currentTab()
        if (engineView.session !== tab.session) {
            engineView.releaseSession()
            engineView.setSession(tab.session)
        }
        tab.session.setActive(true)
        cancelPageTimer()
        clearFailure()
        if (!tab.session.isOpen && !tab.pendingPopup) {
            tab.session.open(runtime)
            if (!tab.atHome && tab.url.isNotBlank()) tab.session.loadUri(tab.url)
        }
        if (tab.failed && !tab.atHome) {
            showFailure("网页加载失败", "这个标签页未能正常显示，请重试或回到主页。")
        } else if (!tab.atHome && tab.url.isNotBlank() && !tab.painted) {
            // Monitor an unfinished tab after switching back to it.
            startPageTimer(tab.session)
        }
        address.setText(if (tab.atHome || tab.url.isBlank() ||
            tab.url.equals("about:blank", ignoreCase = true)) "" else tab.url)
        showContent(tab)
        updateControls()
        loading.visibility = View.GONE
    }

    private fun showContent(tab: BrowserTab) {
        val empty = tab.url.isBlank() || tab.url.equals("about:blank", ignoreCase = true)
        val home = tab.atHome || empty
        homeView.visibility = if (home) View.VISIBLE else View.GONE
        engineView.visibility = if (home) View.GONE else View.VISIBLE
        if (home) {
            clearFailure()
            if (::loading.isInitialized) loading.visibility = View.GONE
        }
    }

    private fun showHome() {
        val tab = currentTab()
        cancelPageTimer()
        clearFailure()
        tab.keepHome = true
        tab.atHome = true
        tab.session.stop()
        if (tab.url.equals("about:blank", ignoreCase = true)) tab.url = ""
        address.setText("")
        hideKeyboard()
        showContent(tab)
        updateControls()
        loading.visibility = View.GONE
    }

    private fun selectedProvider(): SearchNavigation.Provider = SearchNavigation.provider(
        getSharedPreferences("cheems_browser", MODE_PRIVATE).getString("search_provider", null)
    )

    private fun chooseSearchProvider() {
        val providers = SearchNavigation.Provider.entries
        AlertDialog.Builder(this)
            .setTitle("选择搜索引擎")
            .setSingleChoiceItems(providers.map { it.label }.toTypedArray(),
                providers.indexOf(selectedProvider())) { dialog, which ->
                val provider = providers[which]
                getSharedPreferences("cheems_browser", MODE_PRIVATE).edit()
                    .putString("search_provider", provider.name).apply()
                providerButton.text = "搜索：${provider.label}  ▾"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun openSearch(text: String) {
        val query = text.trim()
        if (query.isBlank()) return
        address.clearFocus()
        hideKeyboard()
        val provider = selectedProvider()
        if (provider == SearchNavigation.Provider.SEARXNG) {
            startActivityForResult(
                Intent(this, CheemsSearchActivity::class.java).putExtra("query", query), 729
            )
        } else {
            openWebUrl(SearchNavigation.searchUrl(query, provider))
        }
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isBlank()) return
        if (input.equals("about:blank", ignoreCase = true)) {
            showHome()
            return
        }
        val url = SearchNavigation.webAddress(input)
        if (url == null) openSearch(input) else openWebUrl(url)
    }

    private fun openWebUrl(url: String) {
        // Explicit web URLs (including SearXNG's HTML page) are loaded once.
        // Never infer a native-search command from a remote URL.
        if (SearchNavigation.webAddress(url) == null) return
        val tab = currentTab()
        tab.keepHome = false
        tab.atHome = false
        tab.url = url
        address.setText(url)
        address.clearFocus()
        hideKeyboard()
        startPageTimer(tab.session)
        showContent(tab)
        loading.progress = 5
        loading.visibility = View.VISIBLE
        if (!tab.session.isOpen) tab.session.open(runtime)
        tab.session.loadUri(url)
        updateControls()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun goBack() {
        cancelPageTimer()
        clearFailure()
        val tab = currentTab()
        if (tab.atHome && tab.url.isNotBlank()) {
            tab.keepHome = false
            tab.atHome = false
            address.setText(tab.url)
            showContent(tab)
            if (!tab.painted || tab.failed) openWebUrl(tab.url)
        } else if (tab.canBack && !tab.atHome) {
            tab.keepHome = false
            tab.session.goBack()
        } else if (!tab.atHome) {
            showHome()
        }
        updateControls()
    }

    private fun updateControls() {
        if (tabs.isEmpty()) return
        val tab = currentTab()
        // 主页叠层可以通过后退返回之前的网页。
        val backEnabled = (tab.atHome && tab.url.isNotBlank()) ||
            (!tab.atHome && (tab.canBack || tab.url.isNotBlank()))
        backButton.alpha = if (backEnabled) 1f else 0.36f
        forwardButton.alpha = if (!tab.atHome && tab.canForward) 1f else 0.36f
        tabButton.text = tabs.size.toString()
    }

    private fun showTabPicker() {
        val names = tabs.mapIndexed { index, tab ->
            "${if (index == activeTab) "●" else "○"}  ${index + 1}. ${tab.title.take(28)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("标签页 · ${tabs.size}")
            .setItems(names) { _, which -> switchTo(which) }
            .setPositiveButton("＋ 新标签") { _, _ -> newTab() }
            .setNeutralButton("关闭当前") { _, _ -> closeCurrentTab() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun closeCurrentTab() {
        if (tabs.isEmpty()) return
        val closed = currentTab()
        if (engineView.session === closed.session) engineView.releaseSession()
        cancelPageTimer()
        clearFailure()
        tabs.removeAt(activeTab)
        closed.session.close()
        if (tabs.isEmpty()) {
            activeTab = 0
            newTab()
        } else {
            // 先把 activeTab 调整到一个存在的下标。
            activeTab = min(activeTab, tabs.lastIndex)
            switchTo(activeTab)
        }
    }

    @Deprecated("Old back dispatcher kept for a minimal Activity without AndroidX")
    override fun onBackPressed() {
        val tab = currentTab()
        if (tab.atHome) {
            if (tab.url.isNotBlank()) goBack() else super.onBackPressed()
        } else {
            goBack()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 729 && resultCode == Activity.RESULT_OK) {
            data?.getStringExtra("open_url")?.let { openWebUrl(it) }
        }
    }

    override fun onDestroy() {
        cancelPageTimer()
        pageHandler.removeCallbacksAndMessages(null)
        engineView.releaseSession()
        tabs.forEach { it.session.close() }
        tabs.clear()
        super.onDestroy()
    }
}
