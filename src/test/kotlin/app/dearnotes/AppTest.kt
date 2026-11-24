package app.dearnotes

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AppTest {

    @Test
    fun allowsHttpHttpsAndMailtoUrls() {
        val allowed = listOf(
            "https://example.com",
            "https://example.com/path?a=1&b=2#top",
            "http://example.com:8080/test",
            "HTTPS://EXAMPLE.COM/Case",
            "https://[::1]:8080/local",
            "https://ja.wikipedia.org/wiki/京都",
            "https://日本語.jp/パス",
            "mailto:alice@example.com",
            "MAILTO:alice@example.com?subject=hello",
        )
        for (url in allowed) {
            assertEquals(url, App.validateExternalUrl(url), "許可されるべき URL: $url")
        }
        assertEquals("https://example.com", App.validateExternalUrl("  https://example.com  "))
    }

    @Test
    fun rejectsUnsafeSchemesPathsAndMalformedUrls() {
        val rejected = listOf(
            "",
            "   ",
            "file:///etc/passwd",
            "FILE:///C:/Windows/System32/calc.exe",
            "/etc/passwd",
            "./script.sh",
            "../secret.txt",
            "example.com",
            "C:\\Windows\\System32\\calc.exe",
            "C:/Windows/win.ini",
            "\\\\server\\share\\file.exe",
            "//server/share/file.exe",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/file.txt",
            "smb://server/share",
            "custom-app://open?id=1",
            "http:///etc/passwd",
            "https://",
            "https://:8080/path",
            "https://user@/path",
            "https://[]/path",
            "http:example.com",
            "https:javascript:alert(1)",
            "mailto:",
            "mailto://evil.example.com",
            "https://example.com/\nfoo",
        )
        for (url in rejected) {
            assertNull(App.validateExternalUrl(url), "拒否されるべき入力: $url")
        }
    }

    @Test
    fun openExternalLinkDelegatesOnlyAllowedUrls() {
        val opened = ArrayList<String>()
        App.openExternalLink("  https://example.com/ok  ") { opened.add(it) }
        App.openExternalLink("mailto:user@example.com") { opened.add(it) }
        App.openExternalLink("file:///etc/passwd") { opened.add(it) }
        App.openExternalLink("\\\\server\\share") { opened.add(it) }
        App.openExternalLink("example.com") { opened.add(it) }
        App.openExternalLink("javascript:alert(1)") { opened.add(it) }

        assertEquals(
            listOf("https://example.com/ok", "mailto:user@example.com"),
            opened,
        )
    }

    @Test
    fun shutdownRunsAllCloseStepsEvenWhenEarlierStepsThrow() {
        val order = ArrayList<String>()
        assertDoesNotThrow {
            App.shutdown(
                flushTitle = {
                    order.add("flushTitle")
                    throw IllegalStateException("タイトル保存失敗")
                },
                closeSaver = {
                    order.add("closeSaver")
                    throw RuntimeException("AutoSaver 終了失敗")
                },
                closeDatabase = {
                    order.add("closeDatabase")
                    throw RuntimeException("DB 終了失敗")
                },
            )
        }
        assertEquals(listOf("flushTitle", "closeSaver", "closeDatabase"), order)
    }
}
