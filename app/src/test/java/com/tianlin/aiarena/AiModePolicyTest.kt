package com.tianlin.aiarena

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏里的"模型 / 思考模式"小字。原则是只读不猜：读到什么写什么，读不到就空着；
 * 「深入」总结前的提醒只在**明确**读到深度思考关着时才出现。
 */
class AiModePolicyTest {

    @Test
    fun labelJoinsWhatWasReadAndSkipsBlanks() {
        assertEquals(
            "Instant · 深度思考 关 · 联网 开",
            AiModePolicy.label(AiModeReading(model = "Instant", thinking = "off", search = "on")),
        )
        assertEquals("GLM-5.2", AiModePolicy.label(AiModeReading(model = "GLM-5.2")))
        assertEquals("Qwen3.7-千问 · 快速 · 深度思考 关", AiModePolicy.label(AiModeReading(model = "Qwen3.7-千问", extra = "快速", thinking = "off")))
        assertEquals("Instant · High · 深度思考 关", AiModePolicy.label(AiModeReading(model = "Instant", extra = "High", thinking = "off")))
        assertEquals("", AiModePolicy.label(AiModeReading()))
    }

    @Test
    fun extraIsNotRepeatedWhenItEqualsTheModel() {
        assertEquals("Expert", AiModePolicy.label(AiModeReading(model = "Expert", extra = "expert")))
    }

    @Test
    fun parseClampsLengthsAndOnlyAcceptsOnOff() {
        val reading = AiModePolicy.parse(
            JSONObject()
                .put("model", " " + "X".repeat(40) + " ")
                .put("thinking", "TRUE")
                .put("search", "off")
                .put("extra", "Y".repeat(40)),
        )
        assertEquals(AiModePolicy.MAX_MODEL_CHARS, reading.model.length)
        assertEquals(AiModePolicy.MAX_EXTRA_CHARS, reading.extra.length)
        // "TRUE" 不是约定的 on / off，当未知处理，别把它显示成"开"
        assertEquals("", reading.thinking)
        assertEquals("off", reading.search)
        assertTrue(AiModePolicy.parse(null).isEmpty)
        assertTrue(AiModePolicy.parse(JSONObject()).isEmpty)
    }

    @Test
    fun thinkingOffOnlyWhenExplicitlyRead() {
        assertTrue(AiModePolicy.thinkingOff(AiModeReading(model = "Instant", thinking = "off")))
        assertFalse(AiModePolicy.thinkingOff(AiModeReading(model = "Instant", thinking = "on")))
        // 豆包 / 智谱读不到开关：不能拿猜测去提醒人
        assertFalse(AiModePolicy.thinkingOff(AiModeReading(model = "对话")))
        assertFalse(AiModePolicy.thinkingOff(AiModeReading()))
    }

    @Test
    fun captionPrefersAnswerTimeLabelThenPageReadingThenUnknownOnlyWhenPageIsUsable() {
        val signedIn = ServiceStatus(state = ConnectionState.SIGNED_IN, modeReading = AiModeReading(model = "GLM-5.2"))
        assertEquals("GLM-5.2", modeCaption(ParticipantRun(), signedIn))
        assertEquals(
            "Expert · 深度思考 开",
            modeCaption(ParticipantRun(requestId = "r", modeLabel = "Expert · 深度思考 开"), signedIn),
        )
        // 网页可用却什么都读不到 → 未知；网页还没打开 → 什么都不写（那不是"未知"，是还没看）
        assertEquals("模式 未知", modeCaption(ParticipantRun(), ServiceStatus(state = ConnectionState.SIGNED_IN)))
        assertEquals("", modeCaption(ParticipantRun(), ServiceStatus(state = ConnectionState.LOADING)))
    }

    @Test
    fun previewLineStripsMarkdownAndTruncates() {
        assertEquals("老年人每天 1.5 到 2 升", previewLine("## **老年人每天 1.5 到 2 升**\n\n- 少量多次"))
        assertEquals("", previewLine("   \n\n"))
        val long = "一".repeat(80)
        assertEquals("一".repeat(60) + "…", previewLine(long))
    }

    @Test
    fun runStatusWordFallsBackToPageStateBeforeTheRoundStarts() {
        assertEquals("就绪", runStatusWord(ParticipantRun(), ServiceStatus(state = ConnectionState.SIGNED_IN)))
        assertEquals("要登录", runStatusWord(ParticipantRun(), ServiceStatus(state = ConnectionState.NEEDS_LOGIN)))
        assertEquals("排队", runStatusWord(ParticipantRun(phase = ParticipantPhase.QUEUED, requestId = "r"), ServiceStatus()))
        assertEquals("没成功", runStatusWord(ParticipantRun(phase = ParticipantPhase.ERROR, requestId = "r"), ServiceStatus()))
        assertEquals("完成", runStatusWord(ParticipantRun(phase = ParticipantPhase.COMPLETE, requestId = "r"), ServiceStatus()))
    }
}
