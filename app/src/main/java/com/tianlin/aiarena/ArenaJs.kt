package com.tianlin.aiarena

/**
 * 注入 WebView 的 JavaScript 字面量转义。
 *
 * 之前用的是 `org.json.JSONObject.quote`。它对引号、反斜杠、`</script>` 和控制字符
 * 都处理得对，但有两个不足：
 *
 * 1. 不转义 U+2028 / U+2029。这两个字符在 ES2019 之前是 JS 的行终止符，
 *    出现在字符串字面量里会直接造成语法错误；用户从网页复制的问题确实会带上它们。
 * 2. 它是 Android 框架类，在 JVM 单元测试里会抛 "not mocked"，
 *    导致所有拼脚本的逻辑只能靠真机 instrumentation 覆盖。
 *
 * 自己实现之后两个问题一起解决，转义行为也能被单元测试锁定。
 */
internal object ArenaJs {
    /** 把任意字符串转成可直接嵌入 JS 源码的双引号字面量（含首尾引号）。 */
    fun quote(value: String): String {
        val builder = StringBuilder(value.length + 16)
        builder.append('"')
        for (char in value) {
            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char == '\b' -> builder.append("\\b")
                // 转义斜杠，字符串里的 </script> 就不会提前结束脚本块。
                char == '/' -> builder.append("\\/")
                char == ' ' -> builder.append("\\u2028")
                char == ' ' -> builder.append("\\u2029")
                char < ' ' || char == '' ->
                    builder.append("\\u").append(String.format("%04x", char.code))
                else -> builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }

    /** 字符串列表转 JS 数组字面量，用于按优先级逐个尝试的选择器表。 */
    fun quoteArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::quote)
}
