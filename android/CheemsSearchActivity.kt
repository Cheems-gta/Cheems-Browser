package io.cheems.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Original Cheems UI + results from the open-source SearXNG meta-search API. */
class CheemsSearchActivity : Activity() {
    companion object {
        private const val DEFAULT_INSTANCE = "https://priv.au"
        private const val PREFS = "cheems_search"
        private const val KEY_INSTANCE = "instance_url"
    }
    private data class Hit(val title: String, val url: String, val snippet: String, val source: String)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private var serial = 0
    private var category = "general"

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun glass(fill: Int, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(fill)
            if (border != null) setStroke(dp(1), border)
        }
    private fun label(s: String, size: Float = 15f, color: Int = Color.WHITE): TextView =
        TextView(this).apply { text = s; textSize = size; setTextColor(color) }
    private fun instance(): String =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INSTANCE, DEFAULT_INSTANCE) ?: DEFAULT_INSTANCE

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(10, 16, 35)
        window.navigationBarColor = Color.rgb(10, 16, 35)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 17, 37))
        }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, ins ->
                val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                ins
            }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(12), 0)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(64)))
        header.addView(label("‹", 32f).apply {
            gravity = Gravity.CENTER
            contentDescription = "返回浏览器"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), -1))
        header.addView(label("🦊  CHEEMS SEARCH", 22f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(label("⚙", 26f, Color.rgb(177, 211, 255)).apply {
            gravity = Gravity.CENTER
            contentDescription = "搜索引擎设置"
            setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(dp(42), -1))

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            background = glass(Color.rgb(31, 43, 71), Color.rgb(75, 95, 133))
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply {
            setMargins(dp(15), dp(6), dp(15), dp(10))
        })
        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(163, 184, 206))
            textSize = 16f
            hint = "搜索整个网络…"
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    search(text.toString())
                    true
                } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(label("搜索", 16f, Color.rgb(150, 216, 255)).apply {
            gravity = Gravity.CENTER
            setOnClickListener { search(input.text.toString()) }
        }, LinearLayout.LayoutParams(dp(50), -1))

        val tabs = LinearLayout(this)
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(49)).apply {
            setMargins(dp(12), 0, dp(12), 0)
        })
        listOf("网页" to "general", "图片" to "images", "视频" to "videos").forEach { (title, key) ->
            tabs.addView(label(title, 14f).apply {
                gravity = Gravity.CENTER
                background = glass(Color.rgb(32, 44, 75))
                setOnClickListener {
                    category = key
                    search(input.text.toString())
                }
            }, LinearLayout.LayoutParams(0, dp(41), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        val scroller = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(9), dp(18), dp(24))
        }
        scroller.addView(list)
        message = label("开源 GeckoView + SearXNG 元搜索", 12f,
            Color.rgb(170, 188, 220)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(message, LinearLayout.LayoutParams(-1, dp(40)).apply {
            setMargins(dp(17), 0, dp(10), 0)
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        input.setText(intent.getStringExtra("query") ?: "")
        search(input.text.toString())
    }

    private fun apiUrl(base: String, q: String, json: Boolean): String =
        Uri.parse(base.trimEnd('/')).buildUpon()
            .appendPath("search")
            .appendQueryParameter("q", q)
            .appendQueryParameter("categories", category)
            .appendQueryParameter("safesearch", "2")
            .apply { if (json) appendQueryParameter("format", "json") }
            .build().toString()

    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        input.setText(q)
        input.setSelection(q.length)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        val id = ++serial
        val base = instance()
        list.removeAllViews()
        list.addView(label("正在搜索：" + q, 16f).apply {
            setPadding(0, dp(22), 0, dp(20))
        })
        progress.visibility = View.VISIBLE
        message.text = "Cheems Search · " + (Uri.parse(base).host ?: base)
        val selectedCategory = category
        worker.execute {
            try {
                val endpoint = Uri.parse(base.trimEnd('/')).buildUpon()
                    .appendPath("search").appendQueryParameter("q", q)
                    .appendQueryParameter("categories", selectedCategory)
                    .appendQueryParameter("safesearch", "2")
                    .appendQueryParameter("format", "json").build().toString()
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                val hits = ArrayList<Hit>()
                try {
                    connection.connectTimeout = 9000
                    connection.readTimeout = 12000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "CheemsBrowser/0.2")
                    if (connection.responseCode != 200)
                        throw IllegalStateException("HTTP " + connection.responseCode)
                    val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        val out = StringBuilder()
                        val buf = CharArray(4096)
                        while (true) {
                            val n = it.read(buf)
                            if (n < 0) break
                            if (out.length + n > 1_500_000) throw IllegalStateException("Too large")
                            out.append(buf, 0, n)
                        }
                        out.toString()
                    }
                    val entries = JSONObject(json).getJSONArray("results")
                    for (i in 0 until minOf(60, entries.length())) {
                        val item = entries.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        val parsed = Uri.parse(url)
                        if (parsed.scheme !in listOf("http", "https") ||
                            parsed.host.isNullOrBlank()) continue
                        hits.add(Hit(item.optString("title", url), url,
                            item.optString("content", ""),
                            item.optString("engine", "SearXNG")))
                    }
                } finally { connection.disconnect() }
                main.post {
                    if (isFinishing || isDestroyed || id != serial) return@post
                    present(q, hits)
                }
            } catch (_: Exception) {
                // Many public instances disable JSON. Fall back to HTML in
                // our own GeckoView instead of scraping someone else's site.
                main.post {
                    if (isFinishing || isDestroyed || id != serial) return@post
                    returnBrowser(apiUrl(base, q, false))
                }
            }
        }
    }

    private fun present(q: String, hits: List<Hit>) {
        progress.visibility = View.GONE
        list.removeAllViews()
        message.text = "Cheems Search · " + hits.size + " 条结果 · SearXNG"
        if (hits.isEmpty()) {
            list.addView(label("没有找到结果，试试其他关键词。", 16f).apply {
                setPadding(0, dp(25), 0, dp(25))
            })
        }
        for (hit in hits) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                background = glass(Color.rgb(28, 39, 67), Color.rgb(65, 81, 115))
                setOnClickListener { returnBrowser(hit.url) }
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
            card.addView(label(hit.title, 18f, Color.rgb(192, 225, 255)).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(label(hit.url, 11f, Color.rgb(138, 201, 175)).apply {
                maxLines = 2
                setPadding(0, dp(6), 0, dp(8))
            })
            if (hit.snippet.isNotBlank()) {
                card.addView(label(hit.snippet.replace(Regex("<[^>]*>"), " ").take(400),
                    14f, Color.rgb(217, 226, 239)))
            }
            card.addView(label("来源 " + hit.source, 11f, Color.rgb(144, 167, 193)).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        list.addView(label("查看完整搜索页面 →", 15f, Color.rgb(175, 218, 255)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(20))
            setOnClickListener { returnBrowser(apiUrl(instance(), q, false)) }
        })
    }

    private fun returnBrowser(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) return
        setResult(RESULT_OK, Intent().putExtra("open_url", url))
        finish()
    }

    private fun settings() {
        val field = EditText(this).apply {
            setSingleLine(true)
            setText(instance())
            hint = "https://search.example.com"
        }
        val shell = LinearLayout(this).apply {
            setPadding(dp(17), 0, dp(17), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Cheems Search · 搜索后端")
            .setMessage("填入你的 SearXNG HTTPS 地址。开启 JSON 可显示 Cheems 原生结果；公共实例若禁用 JSON，会自动切换到 GeckoView 网页结果。")
            .setView(shell)
            .setPositiveButton("保存") { _, _ ->
                val value = field.text.toString().trim().trimEnd('/')
                val uri = Uri.parse(value)
                if (uri.scheme != "https" || uri.host.isNullOrEmpty() ||
                    uri.userInfo != null || uri.query != null || uri.fragment != null) {
                    AlertDialog.Builder(this)
                        .setMessage("请输入正确的 HTTPS 地址，例如 https://search.example.com")
                        .setPositiveButton("确定", null).show()
                } else {
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_INSTANCE, value).apply()
                    search(input.text.toString())
                }
            }
            .setNeutralButton("恢复公共实例") { _, _ ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_INSTANCE, DEFAULT_INSTANCE).apply()
                search(input.text.toString())
            }
            .setNegativeButton("取消", null).show()
    }

    override fun onDestroy() {
        serial++
        worker.shutdownNow()
        super.onDestroy()
    }
}
