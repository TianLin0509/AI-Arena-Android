package com.tianlin.aiarena

/**
 * 各家手机网页能否「先放附件、再提问」。2026-09-15 在真实登录的隔离模拟器上逐家核对：
 * DeepSeek、豆包、Kimi、千问、元宝的输入区都有草稿附件；智谱手机网页只有「选图即发问」的旧入口，
 * 桌面版在手机 WebView 里会进入安全验证，不能也不应绕过。
 */
object ArenaAttachmentSupport {
    private val capable = setOf(
        ArenaService.DEEPSEEK,
        ArenaService.DOUBAO,
        ArenaService.KIMI,
        ArenaService.QWEN,
        ArenaService.YUANBAO,
    )

    /** 千问、元宝把图片和文件放在两个不同的上传菜单项里，圆桌一次只走其中一个。 */
    private val singleKindPerUpload = setOf(ArenaService.QWEN, ArenaService.YUANBAO)

    fun supports(service: ArenaService): Boolean = service in capable

    fun unsupportedReason(service: ArenaService): String = when (service) {
        ArenaService.ZHIPU -> "智谱手机网页没有先放附件再提问的入口，这一轮收不到附件；可以换其他成员或去掉附件"
        else -> "${service.displayName} 暂不支持圆桌附件，可以换其他成员或去掉附件"
    }

    fun isMixed(attachments: List<ArenaAttachment>): Boolean {
        val images = attachments.count { it.mimeType.startsWith("image/") }
        return images in 1 until attachments.size
    }

    /** 发送前逐家检查；null = 这一家可以尝试上传。 */
    fun sendError(service: ArenaService, attachments: List<ArenaAttachment>): String? = when {
        attachments.isEmpty() -> null
        !supports(service) -> unsupportedReason(service)
        service in singleKindPerUpload && isMixed(attachments) ->
            "${service.displayName} 一次只能收图片或文件中的一类，这次没有发送；请把图片和文件分开提问"
        else -> null
    }

    /** 选好附件后在输入区下方给出的提示；null = 所选成员都能收到。 */
    fun notice(services: List<ArenaService>, attachments: List<ArenaAttachment>): String? {
        if (attachments.isEmpty()) return null
        val lines = mutableListOf<String>()
        val unsupported = services.filterNot(::supports)
        if (unsupported.isNotEmpty()) {
            lines += "${unsupported.joinToString("、") { it.displayName }} 暂时收不到附件，这一轮会显示没成功，其他成员照常回答。"
        }
        val mixed = services.filter { it in singleKindPerUpload && isMixed(attachments) }
        if (mixed.isNotEmpty()) {
            lines += "${mixed.joinToString("、") { it.displayName }} 一次只能收图片或文件中的一类，图片和文件混着发时它们会没成功。"
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
