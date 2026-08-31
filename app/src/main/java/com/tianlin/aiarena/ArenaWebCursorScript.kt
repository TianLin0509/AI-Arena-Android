package com.tianlin.aiarena


internal object ArenaWebCursorScript {
    private const val STORAGE_PREFIX = "__ai_arena_cursor_"

    fun prepare(service: ArenaService, requestId: String): String {
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
                assistantBaseline,
                userBaseline,
                startedAt: Date.now(),
                initialUrl: location.href
              };
              window.__aiArenaRequests[requestId] = state;
              try { sessionStorage.setItem(${ArenaJs.quote(STORAGE_PREFIX)} + requestId, JSON.stringify(state)); } catch (_) {}
              return JSON.stringify(state);
            })();
        """.trimIndent()
    }

    fun bind(service: ArenaService, requestId: String): String {
        val latestUser = latestUserExpression(service)
        return """
            (function() {
              const requestId = ${ArenaJs.quote(requestId)};
              const user = $latestUser;
              if (!user) return false;
              user.setAttribute('data-ai-arena-request', requestId);
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
        ArenaService.DEEPSEEK -> "($userCountDeepSeek) > Number(state.userBaseline || 0)"
        ArenaService.DOUBAO -> "($userCountDoubao) > Number(state.userBaseline || 0)"
        ArenaService.KIMI -> "($userCountKimi) > Number(state.userBaseline || 0)"
        ArenaService.QWEN -> "($userCountQwen) > Number(state.userBaseline || 0)"
        ArenaService.YUANBAO -> "($userCountYuanbao) > Number(state.userBaseline || 0)"
        ArenaService.ZHIPU -> "($userCountZhipu) > Number(state.userBaseline || 0)"
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
    }

    private fun userCountExpression(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> userCountDeepSeek
        ArenaService.DOUBAO -> userCountDoubao
        ArenaService.KIMI -> userCountKimi
        ArenaService.QWEN -> userCountQwen
        ArenaService.YUANBAO -> userCountYuanbao
        ArenaService.ZHIPU -> userCountZhipu
    }

    private fun latestUserExpression(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> "(function() { const root = document.querySelector('.ds-virtual-list-visible-items'); if (!root) return null; return Array.from(root.children).filter(function(row) { return !row.querySelector('.ds-markdown') && (row.innerText || row.textContent || '').trim().length > 0; }).pop() || null; })()"
        ArenaService.DOUBAO -> "Array.from(document.querySelectorAll('[class*=v_list_row][data-observe-row]')).filter(function(row) { return !!row.querySelector('[class*=bg-g-send]'); }).pop() || null"
        ArenaService.KIMI -> "Array.from(document.querySelectorAll('.chat-content-item-user')).pop() || null"
        ArenaService.QWEN -> "Array.from(document.querySelectorAll('.message-card-wrap.question, [class*=user] [class*=content], [class*=human] [class*=text]')).pop() || null"
        ArenaService.YUANBAO -> "Array.from(document.querySelectorAll('.agent-chat__list__item--human, [class*=user-message], [class*=user] [class*=content]')).pop() || null"
        ArenaService.ZHIPU -> "Array.from(document.querySelectorAll('.conversation.question, [data-role=user], [class*=user-message]')).pop() || null"
    }

    private const val userCountDeepSeek = "(function() { const root = document.querySelector('.ds-virtual-list-visible-items'); if (!root) return 0; return Array.from(root.children).filter(function(row) { return !row.querySelector('.ds-markdown') && (row.innerText || row.textContent || '').trim().length > 0; }).length; })()"
    private const val userCountDoubao = "Array.from(document.querySelectorAll('[class*=v_list_row][data-observe-row]')).filter(function(row) { return !!row.querySelector('[class*=bg-g-send]'); }).length"
    private const val userCountKimi = "document.querySelectorAll('.chat-content-item-user').length"
    private const val userCountQwen = "document.querySelectorAll('.message-card-wrap.question, [class*=user] [class*=content], [class*=human] [class*=text]').length"
    private const val userCountYuanbao = "document.querySelectorAll('.agent-chat__list__item--human, [class*=user-message], [class*=user] [class*=content]').length"
    private const val userCountZhipu = "document.querySelectorAll('.conversation.question, [data-role=user], [class*=user-message]').length"
}
