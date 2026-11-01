#!/usr/bin/env python3
"""Apply Cheems Search V0.2 changes to original zipped GeckoView source.
Fail closed if upstream lines change, instead of silently producing a bad APK.
"""
from pathlib import Path

p = Path("source/CheemsGeckoBrowser/app/src/main/java/io/cheems/browser/MainActivity.kt")
s = p.read_text(encoding="utf-8")
def change(old, new):
    global s
    n = s.count(old)
    if n != 1:
        raise RuntimeError(f"Expected one match, found {n}: {old[:100]!r}")
    s = s.replace(old, new, 1)

change(
    'private const val SEARCH_ENDPOINT = "https://www.bing.com/search?q="',
    'private const val DEFAULT_SEARCH_ENDPOINT = "https://searx.ononoki.org/search?q="'
)
change(
    'progressBackgroundTintList = Color.rgb(8, 13, 28)',
    'progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(8, 13, 28))'
)
change(
    'setOnClickListener { showHome() }\n        }\n        toolbar.addView(brand,',
    'setOnClickListener { showHome() }\n            setOnLongClickListener { showSearchSettings(); true }\n        }\n        toolbar.addView(brand,'
)
change(
    'return if (looksLikeHost) "https://$query" else SEARCH_ENDPOINT + Uri.encode(query)',
    'return if (looksLikeHost) "https://$query" else searchEndpoint() + Uri.encode(query)'
)
change(
    'center.addView(label("快捷入口", 14f,',
    '''val searchSettings = label("🦊 Cheems Search  ·  设置搜索来源 ↗", 15f).apply {
                background = rounded(Color.argb(70, 190, 220, 255), 18,
                    Color.argb(70, 255, 255, 255))
                setOnClickListener { showSearchSettings() }
            }
            center.addView(searchSettings, withMargins(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(53), 0, 14))
            center.addView(label("快捷入口", 14f,'''
)
change(
    'center.addView(label("CHEEMS  ·  V0.1", 12f,',
    'center.addView(label("CHEEMS  ·  V0.2  ·  GeckoView + SearXNG", 12f,'
)
change(
    '    private fun hideKeyboard() {',
    '''    /** HTTPS-only configurable, open-source SearXNG-compatible web search. */
    private fun searchEndpoint(): String =
        getSharedPreferences("cheems_search", Context.MODE_PRIVATE)
            .getString("endpoint", DEFAULT_SEARCH_ENDPOINT) ?: DEFAULT_SEARCH_ENDPOINT

    private fun saveSearchEndpoint(url: String) {
        getSharedPreferences("cheems_search", Context.MODE_PRIVATE)
            .edit().putString("endpoint", url).apply()
    }

    private fun showSearchSettings() {
        val labels = arrayOf(
            "SearXNG · 节点一（默认）",
            "SearXNG · 节点二",
            "SearXNG · 节点三",
            "自定义 Cheems Search 服务 / SearXNG"
        )
        val endpoints = arrayOf(
            "https://searx.ononoki.org/search?q=",
            "https://libresearch.space/search?q=",
            "https://baresearch.org/search?q="
        )
        AlertDialog.Builder(this)
            .setTitle("🦊 Cheems Search · 搜索引擎")
            .setMessage("搜索网页使用开源 SearXNG。公共节点由第三方运营，可能限制访问或短暂不可用。长按首页左上角 🦊 可再次设置。")
            .setItems(labels) { _, choice ->
                if (choice < endpoints.size) {
                    saveSearchEndpoint(endpoints[choice])
                    android.widget.Toast.makeText(this,
                        "已切换搜索源：" + labels[choice],
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showCustomSearchEndpoint()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomSearchEndpoint() {
        val edit = EditText(this).apply {
            setSingleLine(true)
            setText(searchEndpoint())
            hint = "https://search.example.com/?q="
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义搜索地址模板")
            .setMessage("填写 HTTPS 搜索入口，必须以 ?q= 或 &q= 结尾。例如部署 Cheems Search 后使用 https://你的域名/?q=")
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = edit.text.toString().trim()
                val u = Uri.parse(v)
                val valid = u.scheme.equals("https", true) &&
                    !u.host.isNullOrBlank() &&
                    (v.endsWith("?q=") || v.endsWith("&q=")) &&
                    !v.contains("#")
                if (valid) {
                    saveSearchEndpoint(v)
                    android.widget.Toast.makeText(this,
                        "已使用自定义 Cheems Search 搜索服务",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this,
                        "请输入 HTTPS 地址，并以 ?q= 或 &q= 结尾",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun hideKeyboard() {'''
)
p.write_text(s, encoding="utf-8")
print("Applied Cheems Search V0.2 patch to", p)
