package com.tianlin.aiarena

import org.json.JSONObject

/**
 * 网页里读到的"当前模型 / 思考模式"。
 *
 * 用户的问题（2026-09-06）：每家 AI 都有深度思考之类的开关，能不能把当前状态摆在状态栏里，
 * 免得家人不知道 AI 是在哪种模式下回答。原则：**只读，绝不猜**——每个站点只填自己真读得到的字段，
 * 读不到就留空，界面显示"模式 未知"。[thinking] / [search] 只取 "on" / "off" / ""（未知）。
 */
data class AiModeReading(
    val model: String = "",
    val thinking: String = "",
    val search: String = "",
    /** 站点自己的补充说法，例如 Kimi 的思考力度 "High"、千问输入区胶囊上的 "快速"。 */
    val extra: String = "",
) {
    val isEmpty: Boolean
        get() = model.isBlank() && thinking.isBlank() && search.isBlank() && extra.isBlank()
}

object AiModePolicy {
    const val ON = "on"
    const val OFF = "off"
    const val MAX_MODEL_CHARS = 24
    const val MAX_EXTRA_CHARS = 16

    fun parse(json: JSONObject?): AiModeReading {
        if (json == null) return AiModeReading()
        return AiModeReading(
            model = json.optString("model").trim().take(MAX_MODEL_CHARS),
            thinking = switch(json.optString("thinking")),
            search = switch(json.optString("search")),
            extra = json.optString("extra").trim().take(MAX_EXTRA_CHARS),
        )
    }

    private fun switch(value: String): String = when (value.trim().lowercase()) {
        ON -> ON
        OFF -> OFF
        else -> ""
    }

    /** 状态栏小字，例如 "Instant · 深度思考 关 · 联网 开"。读数全空时返回空串，由界面决定怎么说"未知"。 */
    fun label(reading: AiModeReading): String = listOfNotNull(
        reading.model.takeIf { it.isNotBlank() },
        reading.extra.takeIf { it.isNotBlank() && !it.equals(reading.model, ignoreCase = true) },
        switchLabel("深度思考", reading.thinking),
        switchLabel("联网", reading.search),
    ).joinToString(" · ")

    private fun switchLabel(name: String, value: String): String? = when (value) {
        ON -> "$name 开"
        OFF -> "$name 关"
        else -> null
    }

    /**
     * 明确读到"深度思考关着"才为 true。「深入」总结前用它决定要不要提醒用户先给队长打开深度思考；
     * 读不到的一律 false——不能拿猜测去打扰人。
     */
    fun thinkingOff(reading: AiModeReading): Boolean = reading.thinking == OFF
}

/**
 * 各站"当前模型 / 思考模式"的只读脚本。所有选择器都是 2026-09-06 在用户登录好的真实页面上采样的：
 *
 * - DeepSeek：根页有一组 radio（Instant / Expert / Vision），对话页顶栏直接写模型名；
 *   输入区 `.ds-toggle-button`（DeepThink / Search）带 aria-pressed，localStorage 里还有
 *   `thinkingEnabled` / `searchEnabled` 兜底。
 * - 豆包：手机版网页**没有**深度思考开关（「更多」是工具菜单，「对话」只切工作任务），只能读输入区的模式名。
 * - Kimi：输入区 `.current-model .name`（Instant / Thinking …）+ `.current-effort`（High …）；Instant 下也会思考，不据此判"关"。
 * - 千问：顶栏模型名 "Qwen3.7-千问"，输入区胶囊 aria-label（快速 / 思考 / 思考研究）。
 * - 元宝：`aria-label="Switch model"` 按钮上的文字（Expert …）。
 * - 智谱：h5 页面没有任何模式控件，但 Vuex 里有 `Home.configuration.home.commonModel`（"GLM-5.2"）
 *   和 `Conversation.selected_model / is_networking`。
 *
 * 这些文字随各站改版会变，所以它是"锦上添花"级的信息：读不到不影响提问。
 */
internal object ArenaWebModeScript {
    val helpers = """
        const modeVis = function(el) { if (!el) return false; try { const r = el.getBoundingClientRect(); const s = window.getComputedStyle(el); return r.width > 1 && r.height > 1 && s.display !== 'none' && s.visibility !== 'hidden'; } catch (_) { return false; } };
        const modeTxt = function(el) { return el ? String(el.innerText || el.textContent || '').replace(/[ \t\n\r]+/g, ' ').trim() : ''; };
        const modePressed = function(el) { const v = el ? el.getAttribute('aria-pressed') : null; return v === 'true' ? 'on' : (v === 'false' ? 'off' : ''); };
    """.trimIndent()

    fun body(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> """
            const dsChecked = document.querySelector('[role=radiogroup] [role=radio][aria-checked=true]');
            if (dsChecked) mode.model = modeTxt(dsChecked).slice(0, 24);
            if (!mode.model) {
              let dsNames = [];
              try { dsNames = (JSON.parse(localStorage.getItem('__ds_remote_feature_store_model')).entries.model_configs.value || []).map(function(m) { return String(m.name || ''); }).filter(Boolean); } catch (_) {}
              // 对话页有两个 .the-header（一个宽度 0 的隐藏副本），只认画在屏幕上的那个
              if (dsNames.length) {
                const hit = Array.from(document.querySelectorAll('.the-header span, header span')).find(function(el) { return el.children.length === 0 && modeVis(el) && dsNames.indexOf(modeTxt(el)) >= 0; });
                if (hit) mode.model = modeTxt(hit).slice(0, 24);
              }
            }
            const dsToggles = Array.from(document.querySelectorAll('.ds-toggle-button, [role=button][aria-pressed]'));
            mode.thinking = modePressed(dsToggles.find(function(el) { return /DeepThink|深度思考/i.test(modeTxt(el)); }));
            mode.search = modePressed(dsToggles.find(function(el) { return /^Search$|联网/i.test(modeTxt(el)); }));
            const dsFlag = function(key) { try { const v = JSON.parse(localStorage.getItem(key)).value; return v === true ? 'on' : (v === false ? 'off' : ''); } catch (_) { return ''; } };
            if (!mode.thinking) mode.thinking = dsFlag('thinkingEnabled');
            if (!mode.search) mode.search = dsFlag('searchEnabled');
        """.trimIndent()
        ArenaService.DOUBAO -> """
            const dbInput = document.querySelector('[contenteditable=true], textarea');
            let dbArea = dbInput;
            for (let i = 0; i < 8 && dbArea && dbArea.parentElement; i++) {
              const parent = dbArea.parentElement;
              if (parent.getBoundingClientRect().height > 420) break;
              dbArea = parent;
            }
            if (dbArea) {
              const hit = Array.from(dbArea.querySelectorAll('span, div')).find(function(el) { return el.children.length === 0 && modeVis(el) && /^(对话|快速|专家|工作任务|自动思考|深度思考|思考)$/.test(modeTxt(el)); });
              if (hit) mode.model = modeTxt(hit);
            }
        """.trimIndent()
        ArenaService.KIMI -> """
            const kmName = document.querySelector('.current-model .name, .model-name .name');
            const kmEffort = document.querySelector('.current-model .current-effort, .model-name .current-effort');
            mode.model = modeTxt(kmName).slice(0, 24);
            mode.extra = modeTxt(kmEffort).slice(0, 16);
            // 真机实测（2026-09-06）：Instant 模式下回答里照样有一段 328 字的思考块，
            // 所以 "Instant" 不等于"深度思考关"。这里只报模式名，思考与否看回答里的痕迹。
            if (/Think|思考/i.test(mode.model)) mode.thinking = 'on';
        """.trimIndent()
        ArenaService.QWEN -> """
            const qwHead = Array.from(document.querySelectorAll('[class*=truncate]')).find(function(el) { return el.children.length === 0 && modeVis(el) && el.getBoundingClientRect().top < 120 && /^Qwen/i.test(modeTxt(el)); });
            if (qwHead) mode.model = modeTxt(qwHead).slice(0, 24);
            const qwCapsule = Array.from(document.querySelectorAll('button[aria-label]')).find(function(el) { return modeVis(el) && /^(快速|思考|思考研究|深度思考)$/.test(el.getAttribute('aria-label') || ''); });
            if (qwCapsule) {
              mode.extra = qwCapsule.getAttribute('aria-label');
              mode.thinking = mode.extra === '快速' ? 'off' : 'on';
            }
        """.trimIndent()
        ArenaService.YUANBAO -> """
            const ybSwitch = document.querySelector('[aria-label="Switch model"], [aria-label*="Switch model"], [aria-label*="切换模型"]');
            if (ybSwitch) mode.model = modeTxt(ybSwitch).slice(0, 24);
        """.trimIndent()
        ArenaService.ZHIPU -> """
            const glmApp = document.querySelector('#app');
            const glmProps = glmApp && glmApp.__vue_app__ && glmApp.__vue_app__.config && glmApp.__vue_app__.config.globalProperties;
            const glmStore = glmProps && glmProps['${'$'}store'];
            const glmState = glmStore && glmStore.state;
            if (glmState) {
              const conv = glmState.Conversation || {};
              const home = (glmState.Home && glmState.Home.configuration && glmState.Home.configuration.home) || {};
              mode.model = String(conv.selected_model || (conv.isPlusModel ? home.memberModel : home.commonModel) || '').slice(0, 24);
              if (typeof conv.is_networking === 'boolean') mode.search = conv.is_networking ? 'on' : 'off';
            }
        """.trimIndent()
    }

    /** 独立探针：网页登录探测通过后跑一次，结果进 [ServiceStatus.modeReading]。 */
    fun build(service: ArenaService): String = """
        (function() {
          const mode = { model: '', thinking: '', search: '', extra: '' };
          try {
            $helpers
            ${body(service)}
          } catch (_) {}
          return JSON.stringify(mode);
        })();
    """.trimIndent()
}
