package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新清单解析。清单是自建站上一份手写/脚本生成的 JSON，格式错了不能让 App 崩，
 * 也不能把"没有新版本"误判成"有新版本"。
 */
class ArenaUpdateCheckerTest {

    private val full = """
        {
          "versionCode": 8,
          "versionName": "0.6.0",
          "apkUrl": "https://ai.lt-stockpartner.tech/android/ai-arena-v0.6.0.apk",
          "apkSizeBytes": 1465044,
          "sha256": "abc123",
          "releasedAt": "2026-09-10",
          "notes": "答案抓全；输入框修复"
        }
    """.trimIndent()

    @Test
    fun parsesEveryField() {
        val info = ArenaUpdateChecker.parse(full)
        assertEquals(8, info.versionCode)
        assertEquals("0.6.0", info.versionName)
        assertEquals("https://ai.lt-stockpartner.tech/android/ai-arena-v0.6.0.apk", info.apkUrl)
        assertEquals(1_465_044L, info.apkSizeBytes)
        assertEquals("abc123", info.sha256)
        assertEquals("2026-09-10", info.releasedAt)
        assertEquals("答案抓全；输入框修复", info.notes)
        assertEquals("1.4 MB", info.sizeLabel)
    }

    @Test
    fun newerOnlyWhenVersionCodeIsStrictlyGreater() {
        val info = ArenaUpdateChecker.parse(full)
        assertTrue(info.isNewerThan(7))
        assertFalse(info.isNewerThan(8))
        assertFalse(info.isNewerThan(9))
    }

    @Test
    fun optionalFieldsMayBeMissing() {
        val info = ArenaUpdateChecker.parse("""{"versionCode": 3, "apkUrl": "https://x.example/a.apk"}""")
        assertEquals(3, info.versionCode)
        assertEquals("版本代码 3", info.versionName)
        assertEquals("", info.sizeLabel)
        assertEquals("", info.notes)
    }

    @Test(expected = Exception::class)
    fun missingVersionCodeIsRejected() {
        ArenaUpdateChecker.parse("""{"apkUrl": "https://x.example/a.apk"}""")
    }

    @Test(expected = Exception::class)
    fun plainHttpApkUrlIsRejected() {
        // 家人手机上装的包必须走 https，否则中间人可以换包
        ArenaUpdateChecker.parse("""{"versionCode": 3, "apkUrl": "http://x.example/a.apk"}""")
    }

    @Test(expected = Exception::class)
    fun garbageIsRejectedInsteadOfCrashingLater() {
        ArenaUpdateChecker.parse("<html>502 Bad Gateway</html>")
    }

    @Test
    fun sizeLabelUsesKilobytesBelowOneMegabyte() {
        val info = ArenaUpdateChecker.parse("""{"versionCode": 3, "apkUrl": "https://x.example/a.apk", "apkSizeBytes": 512000}""")
        assertEquals("500 KB", info.sizeLabel)
    }
}
