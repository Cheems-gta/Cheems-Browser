package io.cheems.browser

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class SearchNavigationTest {
    @Test fun defaultSearchDoesNotDependOnSearxng() {
        assertEquals(SearchNavigation.Provider.BING, SearchNavigation.provider(null))
        assertEquals(SearchNavigation.Provider.BING, SearchNavigation.provider("obsolete"))
        assertEquals("www.bing.com", URI(SearchNavigation.searchUrl("rog", SearchNavigation.provider(null))).host)
    }
    @Test fun chineseAndReservedCharactersRoundTrip() {
        val query = "我的世界 C++ & 猫 #1 🦊"
        for (provider in SearchNavigation.Provider.entries.filter { it != SearchNavigation.Provider.SEARXNG }) {
            val uri = URI(SearchNavigation.searchUrl(query, provider))
            assertEquals(query, URLDecoder.decode(uri.rawQuery.substringAfter('='), "UTF-8"))
            assertNull(uri.fragment)
        }
    }
    @Test fun explicitSearchPagesRemainWebPages() {
        val urls = listOf("https://priv.au/search?q=rog", "https://search.example.com/search?q=%E4%B8%AD%E6%96%87", "https://www.bing.com/search?q=rog")
        urls.forEach { assertEquals(it, SearchNavigation.webAddress(it)) }
    }
    @Test fun addressesAndKeywordsAreDistinguished() {
        assertEquals("https://example.com/path?a=1", SearchNavigation.webAddress("example.com/path?a=1"))
        assertEquals("https://例子.中国", SearchNavigation.webAddress("例子.中国"))
        assertEquals("https://localhost:8080/test", SearchNavigation.webAddress("localhost:8080/test"))
        assertEquals("http://127.0.0.1:9000/", SearchNavigation.webAddress("http://127.0.0.1:9000/"))
        listOf("我的世界", "rog", "rog.com 游戏", "javascript:alert(1)", "file:///tmp/a", "http://", "foo:bar.com", "localhost:99999", "https://u:p@example.com").forEach {
            assertNull(it, SearchNavigation.webAddress(it))
        }
    }
    @Test fun customServersRequireHttpsAndNoCredentials() {
        assertTrue(SearchNavigation.validSearchServer("https://search.example.com/searx"))
        listOf("", "http://example.com", "https://u:p@example.com", "https://example.com?q=x", "https://example.com/#x").forEach {
            assertFalse(it, SearchNavigation.validSearchServer(it))
        }
    }
}
