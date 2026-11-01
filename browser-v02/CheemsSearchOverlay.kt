package io.cheems.browser

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cheems Search: Native results renderer powered by an open-source SearXNG backend.
 * Public SearXNG instances often disable JSON. In that case we load their real
 * HTML results page in GeckoView, without ever falling back to Bing by name.
 */
class CheemsSearchOverlay(
    context: Context,
    private val openResult: (String) -> Unit,
    private val openHtmlResults: (String) -> Unit
) : ScrollView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(17), px(28), px(17), px(38))
    }
    private var generation = 0

    init {
        isFillViewport = true
        setBackgroundColor(Color.rgb(10, 15, 31))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun px(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = px(radius).toFloat()
        setColor(color)
        setStroke(px(1), Color.rgb(54, 73, 109))
    }
    private fun line(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(px(3).toFloat(), 1f)
        }
    private fun addText(text: String, size: Float, color: Int, top: Int = 0, bold: Boolean = false) {
        body.addView(line(text, size, color, bold), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top) })
    }
    private fun linkButton(text: String, onClick: () -> Unit) {
        body.addView(line(text, 14f, Color.rgb(165, 210, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(px(10), px(12), px(10), px(12))
            background = shape(Color.rgb(32, 48, 78), 15)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(18)
        })
    }

    fun search(query: String, base: String) {
        generation++
        val request = generation
        val html = "$base/search?q=${Uri.encode(query)}&safesearch=1"
        body.removeAllViews()
        addText("🦊 Cheems Search", 27f, Color.WHITE, bold = true)
        addText("搜索：$query", 15f, Color.rgb(171, 191, 221), top = 10)
        addText("正在从 SearXNG 获取实时网页结果…", 14f, Color.rgb(170, 185, 210), top = 28)
        linkButton("使用网页模式搜索") { openHtmlResults(html) }

        Thread {
            val result = try {
                val conn = URL("$html&format=json").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 9000
                    conn.readTimeout = 12000
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("User-Agent", "CheemsBrowser/0.2 (GeckoView; SearXNG client)")
                    val status = conn.responseCode
                    if (status != 200) throw IllegalStateException("HTTP $status")
                    val type = conn.contentType ?: ""
                    if (!type.contains("json", ignoreCase = true)) throw IllegalStateException("非 JSON 响应")
                    val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(raw)
                } finally { conn.disconnect() }
            } catch (_: Exception) { null }
            handler.post {
                if (request != generation || visibility != View.VISIBLE) return@post
                if (result == null) {
                    // A public instance normally has HTML only; it still offers real live search.
                    openHtmlResults(html)
                } else {
                    showResults(query, result, html)
                }
            }
        }.start()
    }

    private fun showResults(query: String, response: JSONObject, fallback: String) {
        body.removeAllViews()
        addText("🦊 Cheems Search", 27f, Color.WHITE, bold = true)
        addText("$query · SearXNG 实时搜索", 15f, Color.rgb(170, 192, 226), top = 10)
        val results = response.optJSONArray("results")
        if (results == null || results.length() == 0) {
            addText("暂无结果，可以切换至网页模式重试。", 15f, Color.LTGRAY, top = 25)
        } else {
            for (i in 0 until minOf(results.length(), 40)) {
                val item = results.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                val title = item.optString("title").ifBlank { url }.take(180)
                val snippet = item.optString("content").replace(Regex("<[^>]*>"), " ").take(420)
                val source = Uri.parse(url).host.orEmpty()
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(Color.rgb(23, 34, 57), 19)
                    setPadding(px(16), px(16), px(16), px(16))
                    isClickable = true
                    setOnClickListener { openResult(url) }
                }
                card.addView(line(title, 17f, Color.rgb(196, 222, 255), true))
                card.addView(line(source, 12f, Color.rgb(132, 192, 173)).apply {
                    setPadding(0, px(6), 0, 0)
                })
                if (snippet.isNotBlank()) {
                    card.addView(line(snippet, 14f, Color.rgb(203, 215, 231)).apply {
                        setPadding(0, px(9), 0, 0)
                    })
                }
                body.addView(card, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(12) })
            }
        }
        linkButton("切换到 SearXNG 网页版") { openHtmlResults(fallback) }
        addText("搜索后端：SearXNG（开源元搜索） · 渲染：Cheems UI", 12f,
            Color.rgb(131, 151, 183), top = 22)
    }
}
