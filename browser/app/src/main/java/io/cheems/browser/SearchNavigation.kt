package io.cheems.browser

import java.net.URI
import java.net.URLEncoder

/** Resolve user input once. Returned web URLs must never re-enter native search. */
object SearchNavigation {
    enum class Provider(val label: String, val endpoint: String) {
        BING("必应", "https://www.bing.com/search?q="),
        BAIDU("百度", "https://www.baidu.com/s?wd="),
        GOOGLE("Google", "https://www.google.com/search?q="),
        SEARXNG("Cheems Search · 自定义 SearXNG", "")
    }

    fun provider(value: String?): Provider =
        Provider.entries.firstOrNull { it.name == value } ?: Provider.BING

    fun searchUrl(query: String, provider: Provider): String {
        require(provider != Provider.SEARXNG)
        return provider.endpoint + URLEncoder.encode(query.trim(), "UTF-8")
    }

    fun webAddress(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            return try {
                val parsed = URI(value)
                if (parsed.rawAuthority.isNullOrBlank() || parsed.rawAuthority.contains('@')) null
                else value
            } catch (_: Exception) { null }
        }
        // Host names, internationalized host names, IPv4, localhost and optional ports.
        // Other URI schemes are treated as text, never executed from the address bar.
        val authority = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) return null
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "")
        if (':' in authority && ((port.toIntOrNull() ?: 0) !in 1..65535)) return null
        if (host.equals("localhost", true) ||
            (host.contains('.') && host.split('.').all { part ->
                part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '-' }
            })) return "https://$value"
        return null
    }

    fun validSearchServer(raw: String): Boolean = try {
        val uri = URI(raw)
        uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (_: Exception) { false }
}
