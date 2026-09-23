package com.tianlin.aiarena


internal object ArenaWebCursorScript {
    private const val STORAGE_PREFIX = "__ai_arena_cursor_"

    fun prepare(service: ArenaService, requestId: String, prompt: String = "", legacyAttachment: Boolean = false): String {
        val selectors = responseSelectors(service).joinToString(",") { ArenaJs.quote(it) }
        val userCount = userCountExpression(service)
        return """
            (function() {
              window.__aiArenaRequests = window.__aiArenaRequests || {};
              const requestId = ${ArenaJs.quote(requestId)};
              const selectors = [$selectors];
              let assistantBaseline = 0;
              for (const selector of selectors) {
                try {
                  const count = document.querySelectorAll(selector).length;
                  if (count > 0) { assistantBaseline = count; break; }
                } catch (_) {}
              }
              const userBaseline = $userCount;
              const state = {
                legacyAttachment: $legacyAttachment,
                assistantBaseline,
                userBaseline,
                startedAt: Date.now(),
                initialUrl: location.href,
                expectedPrompt: ${ArenaJs.quote(prompt)}.replace(/\s+/g, ' ').trim()
              };
              ${if (service == ArenaService.DEEPSEEK) """
              // DeepSeek virtual keys are local to a document, unlike a server UUID.
              window.__aiArenaDeepSeekDocument = window.__aiArenaDeepSeekDocument || Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
              state.documentToken = window.__aiArenaDeepSeekDocument;
              """.trimIndent() else ""}
              ${ArenaWebMessageIdentity.helper(service)}
              const beforeUsers = ${ArenaWebMessageIdentity.users(service)};
              state.beforeUserIds = beforeUsers.map(arenaUserId).filter(Boolean);
              state.beforeUserTexts = beforeUsers.map(arenaUserText);
              state.beforeQueueIds = Array.from(document.querySelectorAll('[data-item-id][data-item-status]')).map(row => row.getAttribute('data-item-id'));
              beforeUsers.forEach(row => row.setAttribute('data-ai-arena-before', requestId));
              window.__aiArenaRequests[requestId] = state;
              try { sessionStorage.setItem(${ArenaJs.quote(STORAGE_PREFIX)} + requestId, JSON.stringify(state)); } catch (_) {}
              return JSON.stringify(state);
            })();
        """.trimIndent()
    }

    fun bind(service: ArenaService, requestId: String, requireIdentity: Boolean = false): String {
        val latestUser = if (ArenaWebMessageIdentity.supported(service)) "(state.expectedPrompt ? arenaFindRequestUser() : ${latestUserExpression(service)})" else latestUserExpression(service)
        return """
            (function() {
              ${stateBootstrap(requestId)}
              ${ArenaWebMessageIdentity.helper(service)}
              if ($requireIdentity && !state.expectedPrompt) return false;
              if (!arenaRequestScopeValid()) return 'scope_changed';
              const user = $latestUser;
              if (!user || ($requireIdentity && !arenaUserId(user))) return false;
              arenaBindRequestUser(user);
              return true;
            })();
        """.trimIndent()
    }

    fun stateBootstrap(requestId: String): String = """
        const requestId = ${ArenaJs.quote(requestId)};
        const cursorKey = ${ArenaJs.quote(STORAGE_PREFIX)} + requestId;
        let state = window.__aiArenaRequests && window.__aiArenaRequests[requestId];
        if (!state) {
          try { state = JSON.parse(sessionStorage.getItem(cursorKey) || 'null'); } catch (_) { state = null; }
        }
        state = state || { assistantBaseline: 0, userBaseline: 0, startedAt: Date.now(), initialUrl: location.href };
    """.trimIndent()

    fun conversationAdvancedExpression(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> "(state.expectedPrompt ? !!arenaFindRequestUser() : ($userCountDeepSeek) > Number(state.userBaseline || 0))"
        ArenaService.DOUBAO -> "(state.expectedPrompt ? !!arenaFindRequestUser() : ($userCountDoubao) > Number(state.userBaseline || 0))"
        ArenaService.KIMI -> "(state.expectedPrompt ? !!arenaFindRequestUser() : ($userCountKimi) > Number(state.userBaseline || 0))"
        ArenaService.QWEN -> "($userCountQwen) > Number(state.userBaseline || 0)"
        ArenaService.YUANBAO -> "($userCountYuanbao) > Number(state.userBaseline || 0)"
        ArenaService.ZHIPU -> "($userCountZhipu) > Number(state.userBaseline || 0)"
        ArenaService.CLAUDE -> "($userCountClaude) > Number(state.userBaseline || 0)"
        ArenaService.CHATGPT -> "($userCountChatGpt) > Number(state.userBaseline || 0)"
        ArenaService.GEMINI -> "($userCountGemini) > Number(state.userBaseline || 0)"
    }

    fun responseSelectors(service: ArenaService): List<String> = when (service) {
        ArenaService.DEEPSEEK -> listOf(
            ".ds-markdown",
            "[class*='assistant-message']",
            "[class*='bot-message']",
            ".markdown-body",
            ".prose",
        )
        ArenaService.DOUBAO -> listOf(
            "[class*='v_list_row'][data-observe-row]:not(:has([class*='bg-g-send'])) .md-box-root",
            "[class*='v_list_row'][data-observe-row]:not(:has([class*='bg-g-send']))",
        )
        ArenaService.KIMI -> listOf(
            ".chat-content-item-assistant .markdown-container",
            ".segment.segment-assistant .markdown-container",
            "[class*='segment-assistant'] .markdown-container",
        )
        ArenaService.QWEN -> listOf(
            "[class*='qk-markdown']",
            ".qk-md-paragraph",
            "[class*='assistant'] [class*='content']",
            "[class*='answer-content']",
        )
        ArenaService.YUANBAO -> listOf(
            "[class*='hyc-content-md']",
            "[class*='hyc-common-markdown']",
            "[class*='assistant'] [class*='content']",
        )
        ArenaService.ZHIPU -> listOf(
            "[class*='assistant'] [class*='markdown']",
            "[class*='assistant'] [class*='content']",
            "[data-role='assistant']",
            "[class*='answer'] [class*='markdown']",
            "[class*='markdown-body']",
        )
        ArenaService.CLAUDE -> listOf(
            ".font-claude-response",
            ".font-claude-message",
            "[data-is-streaming] .standard-markdown",
        )
        ArenaService.CHATGPT -> listOf(
            "[data-message-author-role='assistant'] .markdown",
            "[data-message-author-role='assistant']",
        )
        ArenaService.GEMINI -> listOf(
            "model-response message-content .markdown",
            "model-response message-content",
        )
    }

    private fun userCountExpression(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> userCountDeepSeek
        ArenaService.DOUBAO -> userCountDoubao
        ArenaService.KIMI -> userCountKimi
        ArenaService.QWEN -> userCountQwen
        ArenaService.YUANBAO -> userCountYuanbao
        ArenaService.ZHIPU -> userCountZhipu
        ArenaService.CLAUDE -> userCountClaude
        ArenaService.CHATGPT -> userCountChatGpt
        ArenaService.GEMINI -> userCountGemini
    }

    private fun latestUserExpression(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> "(function() { const root = document.querySelector('.ds-virtual-list-visible-items'); if (!root) return null; return Array.from(root.children).filter(function(row) { return !row.querySelector('.ds-markdown') && (row.innerText || row.textContent || '').trim().length > 0; }).pop() || null; })()"
        ArenaService.DOUBAO -> "Array.from(document.querySelectorAll('[class*=v_list_row][data-observe-row]')).filter(function(row) { return !!row.querySelector('[class*=bg-g-send]'); }).pop() || null"
        ArenaService.KIMI -> "Array.from(document.querySelectorAll('.chat-content-item-user')).pop() || null"
        ArenaService.QWEN -> "Array.from(document.querySelectorAll('.message-card-wrap.question, [class*=user] [class*=content], [class*=human] [class*=text]')).pop() || null"
        ArenaService.YUANBAO -> "Array.from(document.querySelectorAll('.agent-chat__list__item--human')).pop() || null"
        ArenaService.ZHIPU -> "Array.from(document.querySelectorAll('.conversation.question, [data-role=user], [class*=user-message]')).pop() || null"
        ArenaService.CLAUDE -> "Array.from(document.querySelectorAll('[data-testid=user-message]')).pop() || null"
        ArenaService.CHATGPT -> "Array.from(document.querySelectorAll('[data-message-author-role=user]')).pop() || null"
        ArenaService.GEMINI -> "Array.from(document.querySelectorAll('user-query')).pop() || null"
    }

    private const val userCountDeepSeek = "(function() { const root = document.querySelector('.ds-virtual-list-visible-items'); if (!root) return 0; return Array.from(root.children).filter(function(row) { return !row.querySelector('.ds-markdown') && (row.innerText || row.textContent || '').trim().length > 0; }).length; })()"
    private val userCountDoubao = "${ArenaWebMessageIdentity.users(ArenaService.DOUBAO)}.length"
    private const val userCountKimi = "document.querySelectorAll('.chat-content-item-user').length"
    private const val userCountQwen = "document.querySelectorAll('.message-card-wrap.question, [class*=user] [class*=content], [class*=human] [class*=text]').length"
    // 2026-09-15: "[class*=user] [class*=content]" also matched a navigation guide popup under .yb-nav__user,
    // so its appearance counted as a sent message and a not-sent question waited five minutes.
    private const val userCountYuanbao = "document.querySelectorAll('.agent-chat__list__item--human').length"
    private const val userCountZhipu = "document.querySelectorAll('.conversation.question, [data-role=user], [class*=user-message]').length"
    private const val userCountClaude = "document.querySelectorAll('[data-testid=user-message]').length"
    private const val userCountChatGpt = "document.querySelectorAll('[data-message-author-role=user]').length"
    private const val userCountGemini = "document.querySelectorAll('user-query').length"
}
