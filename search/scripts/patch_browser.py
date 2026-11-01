#!/usr/bin/env python3
"""Apply the Cheems Search V0.2 integration to the original V0.1 source archive.
Used at build time until the Android Studio project is migrated into Git.
"""
from pathlib import Path

root = Path("source/CheemsGeckoBrowser")
kt_path = root / "app/src/main/java/io/cheems/browser/MainActivity.kt"
gradle_path = root / "app/build.gradle.kts"
text = kt_path.read_text(encoding="utf-8")

def substitute(old: str, new: str) -> None:
    global text
    assert text.count(old) == 1, f"Expected exactly one match: {old[:100]!r}"
    text = text.replace(old, new, 1)

substitute(
    'private const val SEARCH_ENDPOINT = "https://www.bing.com/search?q="',
    'private const val DEFAULT_SEARCH_BASE = "https://search.inetol.net"'
)
substitute(
    'return if (looksLikeHost) "https://$query" else SEARCH_ENDPOINT + Uri.encode(query)',
    'return if (looksLikeHost) "https://$query" else searchEndpoint() + Uri.encode(query)'
)
substitute(
    '    private fun normalizeAddress(text: String): String {',
    '''    // A SearXNG search instance is a service, not a new rendering engine.
    // The public instance makes searches work before Cheems Search is deployed.
    private fun searchEndpoint(): String {
        val base = getSharedPreferences("cheems_search", MODE_PRIVATE)
            .getString("instance", DEFAULT_SEARCH_BASE)
            ?.trim()?.trimEnd('/') ?: DEFAULT_SEARCH_BASE
        return "$base/search?q="
    }

    private fun showSearchSettings() {
        val prefs = getSharedPreferences("cheems_search", MODE_PRIVATE)
        val entry = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("instance", DEFAULT_SEARCH_BASE))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("🦊 Cheems Search · 搜索源")
            .setMessage("默认是开源 SearXNG 公共实例。部署自己的 Cheems Search 后，填写它的 HTTPS 域名（不带 /search）。搜索内容将发送到所选实例。")
            .setView(entry)
            .setPositiveButton("保存") { _, _ ->
                val candidate = entry.text.toString().trim().trimEnd('/')
                val uri = Uri.parse(candidate)
                if (uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    uri.path.isNullOrBlank()) {
                    prefs.edit().putString("instance", candidate).apply()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("请输入 HTTPS 根地址，例如 https://search.example.com")
                        .setPositiveButton("知道了", null).show()
                }
            }
            .setNeutralButton("恢复公共实例") { _, _ ->
                prefs.edit().remove("instance").apply()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun normalizeAddress(text: String): String {'''
)
substitute(
    '            center.addView(label("快捷入口", 14f,',
    '''            center.addView(label("🦊 Cheems Search · 修改搜索源", 15f,
                Color.rgb(182, 222, 255)).apply {
                background = rounded(Color.argb(56, 255, 255, 255), 17,
                    Color.argb(70, 255, 255, 255))
                setOnClickListener { showSearchSettings() }
            }, withMargins(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), 0, 8))

            center.addView(label("快捷入口", 14f,'''
)
substitute('label("CHEEMS  ·  V0.1", 12f,', 'label("CHEEMS  ·  V0.2  ·  SEARXNG + GECKO", 12f,')
kt_path.write_text(text, encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")
assert 'versionCode = 1' in gradle and 'versionName = "0.1.0"' in gradle
gradle = gradle.replace('versionCode = 1', 'versionCode = 2')
gradle = gradle.replace('versionName = "0.1.0"', 'versionName = "0.2.0"')
gradle_path.write_text(gradle, encoding="utf-8")
print("Cheems Search V0.2: SearXNG provider + editable HTTPS instance + version 2")
