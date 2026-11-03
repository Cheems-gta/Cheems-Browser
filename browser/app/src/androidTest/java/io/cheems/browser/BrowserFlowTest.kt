package io.cheems.browser

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class BrowserFlowTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var server: ServerSocket
    private val workers = Executors.newCachedThreadPool()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var base: String

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("cheems_browser", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cheems_search", Context.MODE_PRIVATE).edit().clear().commit()
        server = ServerSocket(0)
        base = "http://127.0.0.1:${server.localPort}"
        workers.execute {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    workers.execute {
                        try {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine()?.split(" ")?.getOrNull(1) ?: "/"
                            while (!input.readLine().isNullOrEmpty()) { }
                            if (path == "/redirect") {
                                it.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fixture $path</title></head>
                                <body style="background:#d9f5e9;color:#152d30;font:24px sans-serif;padding:20px"><h1>中文网页</h1><p>Fixture $path</p>
                                <a href="/next">Next page</a><br><br><a href="/popup" target="_blank">Open new tab</a><br><br>
                                <a href="/redirect">Redirect</a><div style="height:1100px">Scrollable content</div><p>End of page</p></body></html>""".toByteArray(Charsets.UTF_8)
                                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                it.getOutputStream().write(html)
                            }
                        }
                        } catch (_: java.io.IOException) {
                            // Browser cancellation/teardown can close a response midway.
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() {
        scenario.close()
        server.close()
        workers.shutdownNow()
    }

    private fun views(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun submit(value: String) {
        scenario.onActivity { activity ->
            val all = views(activity.findViewById(android.R.id.content))
            (all.single { it.contentDescription == "地址栏" } as EditText).setText(value)
            all.single { it.contentDescription == "访问或搜索" }.performClick()
        }
    }
    private fun clickNative(description: String) {
        scenario.onActivity { activity ->
            views(activity.findViewById(android.R.id.content)).single { it.contentDescription == description }.performClick()
        }
    }
    private fun waitState(label: String, timeout: Long = 20000, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(100)
        }
        screenshot("failure")
        var state = ""
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            state = "url=${address(activity)} painted=${currentPainted(activity)} home=${root.findViewWithTag<View>("browser_home").visibility} failure=${root.findViewWithTag<View>("browser_failure").visibility}"
        }
        fail("Timed out: $label ($state)")
    }
    private fun address(activity: MainActivity): String =
        (views(activity.findViewById(android.R.id.content)).single { it.contentDescription == "地址栏" } as EditText).text.toString()

    private fun currentPainted(activity: MainActivity): Boolean {
        val method = MainActivity::class.java.getDeclaredMethod("currentTab").apply { isAccessible = true }
        val tab = method.invoke(activity)
        return tab.javaClass.getDeclaredField("painted").apply { isAccessible = true }.getBoolean(tab)
    }
    private fun rendered(path: String) {
        waitState("render $path") { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            address(activity) == base + path &&
                root.findViewWithTag<View>("browser_home").visibility == View.GONE &&
                root.findViewWithTag<View>("browser_failure").visibility == View.GONE
        }
        // Check visible webpage content rather than a paint callback: BFCache
        // history restoration need not emit a second first-contentful-paint.
        assertTrue("Missing actual webpage content: $path",
            device.wait(Until.hasObject(By.text("Fixture $path")), 15000))
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File("/sdcard/Download/cheems-verification").apply { mkdirs() }
        device.takeScreenshot(File(directory, "$name.png"))
    }

    @Test fun rendersChineseScrollsAndOpensNewTab() {
        submit("$base/home")
        rendered("/home")
        screenshot("01-chinese-page")
        // Verify the actual engine's scroll and link handling, not a mock screen.
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 3, 20)
        device.swipe(device.displayWidth / 2, device.displayHeight / 3,
            device.displayWidth / 2, device.displayHeight * 3 / 4, 20)
        assertTrue(device.wait(Until.hasObject(By.text("Open new tab")), 10000))
        device.findObject(By.text("Open new tab")).click()
        rendered("/popup")
        scenario.onActivity { activity ->
            assertEquals("2", (views(activity.findViewById(android.R.id.content))
                .single { it.contentDescription == "管理标签页" } as TextView).text.toString())
        }
        screenshot("02-popup-rendered")
    }

    @Test fun homeBackForwardAndErrorsRecover() {
        submit("$base/home"); rendered("/home")
        submit("$base/next"); rendered("/next")
        clickNative("主页")
        clickNative("后退"); rendered("/next")
        clickNative("后退"); rendered("/home")
        clickNative("前进"); rendered("/next")
        submit("$base/redirect"); rendered("/next")
        val deadPort = ServerSocket(0).use { it.localPort }
        submit("http://127.0.0.1:$deadPort/unreachable")
        waitState("native network error") {
            it.findViewById<ViewGroup>(android.R.id.content)
                .findViewWithTag<View>("browser_failure").visibility == View.VISIBLE
        }
        screenshot("03-network-error")
        clickNative("主页")
        submit("$base/home"); rendered("/home")
    }

    @Test fun searchDefaultsToBingAndProviderPersists() {
        submit("我的世界 C++")
        waitState("Bing URL") { address(it) == SearchNavigation.searchUrl("我的世界 C++", SearchNavigation.Provider.BING) }
        clickNative("主页")
        clickNative("选择搜索引擎")
        assertTrue(device.wait(Until.hasObject(By.text("百度")), 5000))
        device.findObject(By.text("百度")).click()
        scenario.recreate()
        waitState("provider persisted") { activity ->
            views(activity.findViewById(android.R.id.content)).any {
                it is TextView && it.contentDescription == "选择搜索引擎" && it.text.contains("百度")
            }
        }
        submit("中文搜索")
        waitState("Baidu URL") { address(it) == SearchNavigation.searchUrl("中文搜索", SearchNavigation.Provider.BAIDU) }
        clickNative("主页")
        screenshot("04-search-provider")
    }

    @Test fun explicitSearchUrlStaysInBrowserAndPopupSessionIsUnopened() {
        submit("$base/search?q=rog")
        rendered("/search?q=rog")
        scenario.onActivity { activity ->
            val view = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<GeckoView>("browser_content")
            val session = view.session!!
            val before = address(activity)
            // The delegate must not open/switch the session before Gecko does.
            session.navigationDelegate!!.onNewSession(session, "$base/popup")
            assertEquals(before, address(activity))
            val field = MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible = true }
            val last = (field.get(activity) as List<*>).last()!!
            val opened = last.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(last)
                as org.mozilla.geckoview.GeckoSession
            assertFalse("Popup sessions must be unopened", opened.isOpen)
        }
    }

    @Test fun unconfiguredNativeSearchHasImmediateRecovery() {
        clickNative("选择搜索引擎")
        assertTrue(device.wait(Until.hasObject(By.text("Cheems Search · 自定义 SearXNG")), 5000))
        device.findObject(By.text("Cheems Search · 自定义 SearXNG")).click()
        submit("rog")
        assertTrue(device.wait(Until.hasObject(By.textContains("尚未配置 SearXNG")), 7000))
        screenshot("05-native-recovery")
        device.findObject(By.text("↗  使用 必应 备用搜索（第三方）")).click()
        waitState("explicit fallback returns to browser") {
            address(it) == SearchNavigation.searchUrl("rog", SearchNavigation.Provider.BING)
        }
        clickNative("主页")
    }
}
