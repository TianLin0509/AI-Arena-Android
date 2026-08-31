package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户提问会被原样拼进注入网页的 JS 源码里，转义漏一类就是任意脚本执行。
 * 这里把每一类危险输入都钉死。
 */
class ArenaJsTest {
    /** 字面量内部的每个引号都必须带反斜杠，否则就能提前闭合字符串。 */
    private fun assertCannotEscapeLiteral(literal: String) {
        assertTrue("必须是完整的双引号字面量", literal.length >= 2)
        assertTrue(literal.startsWith("\"") && literal.endsWith("\""))
        val body = literal.substring(1, literal.length - 1)
        var index = 0
        while (index < body.length) {
            when (body[index]) {
                '\\' -> index += 2
                '"' -> throw AssertionError("字面量内部出现未转义的引号：$literal")
                else -> index += 1
            }
        }
    }

    @Test
    fun plainTextIsWrappedInDoubleQuotes() {
        assertEquals("\"帮我比较几种家庭旅行方案\"", ArenaJs.quote("帮我比较几种家庭旅行方案"))
    }

    @Test
    fun quotesAndBackslashesCannotBreakOutOfTheLiteral() {
        assertEquals("\"a\\\"b\"", ArenaJs.quote("a\"b"))
        assertEquals("\"a\\\\b\"", ArenaJs.quote("a\\b"))
        // 经典逃逸尝试：先闭合字符串再插入代码。
        assertCannotEscapeLiteral(ArenaJs.quote("\"; alert(1); \""))
        // 反斜杠结尾的输入若不转义，会把我们补的收尾引号变成转义引号。
        assertCannotEscapeLiteral(ArenaJs.quote("trailing backslash \\"))
        assertCannotEscapeLiteral(ArenaJs.quote("\\\"; alert(1); //"))
    }

    @Test
    fun scriptClosingTagIsNeutralised() {
        val escaped = ArenaJs.quote("</script><script>alert(1)</script>")
        assertFalse("斜杠必须被转义，否则能提前结束脚本块", escaped.contains("</script>"))
        assertTrue(escaped.contains("<\\/script>"))
    }

    @Test
    fun newlinesAndControlCharactersBecomeEscapes() {
        assertEquals("\"a\\nb\"", ArenaJs.quote("a\nb"))
        assertEquals("\"a\\rb\"", ArenaJs.quote("a\rb"))
        assertEquals("\"a\\tb\"", ArenaJs.quote("a\tb"))
        assertEquals("\"a\\u0000b\"", ArenaJs.quote("a\u0000b"))
        assertEquals("\"a\\u001fb\"", ArenaJs.quote("a\u001Fb"))
        assertEquals("\"a\\u007fb\"", ArenaJs.quote("a\u007Fb"))
    }

    @Test
    fun lineAndParagraphSeparatorsAreEscaped() {
        // 这是 JSONObject.quote 漏掉的一类：U+2028 / U+2029 在旧版 JS 里是行终止符，
        // 直接嵌进字面量会造成语法错误。用户从网页复制文字时经常会带上它们。
        assertEquals("\"a\\u2028b\"", ArenaJs.quote("a\u2028b"))
        assertEquals("\"a\\u2029b\"", ArenaJs.quote("a\u2029b"))
    }

    @Test
    fun emojiAndAstralCharactersSurviveUnchanged() {
        val text = "合影 👨 完成"
        assertEquals("\"$text\"", ArenaJs.quote(text))
    }

    @Test
    fun emptyStringStillProducesAValidLiteral() {
        assertEquals("\"\"", ArenaJs.quote(""))
    }

    @Test
    fun quoteArrayProducesAJsArrayOfEscapedStrings() {
        assertEquals(
            """["#chat-input","textarea[placeholder]"]""",
            ArenaJs.quoteArray(listOf("#chat-input", "textarea[placeholder]")),
        )
        assertEquals("[]", ArenaJs.quoteArray(emptyList()))
    }

    @Test
    fun everySendButtonSelectorSurvivesQuoting() {
        ArenaService.entries.forEach { service ->
            val array = ArenaJs.quoteArray(ArenaWebViewPool.sendButtonSelectors(service))
            assertTrue(array.startsWith("[") && array.endsWith("]"))
            // 选择器里的单引号在 JS 双引号字面量中不需要转义；
            // 过度转义会产出 \' 这种在 CSS 里无效的内容。
            assertFalse(array.contains("\\'"))
        }
    }
}
