package com.tianlin.aiarena

/** Provider message identity shared by submission acknowledgement and answer extraction. */
internal object ArenaWebMessageIdentity {
    fun supported(service: ArenaService) = service == ArenaService.DOUBAO || service == ArenaService.KIMI

    // Doubao replaced v_list_row with message-box-target-id wrappers in September 2026.
    const val doubaoRows = "Array.from(document.querySelectorAll('[data-target-id=message-box-target-id], [class*=v_list_row][data-observe-row]')).filter(row => !row.parentElement?.closest('[data-target-id=message-box-target-id], [class*=v_list_row][data-observe-row]'))"

    fun users(service: ArenaService): String = when (service) {
        ArenaService.DOUBAO -> "$doubaoRows.filter(row => !!row.querySelector('[data-send-message-boundary], [class*=bg-g-send]'))"
        ArenaService.KIMI -> "Array.from(document.querySelectorAll('.chat-content-item-user:not(.awaiting-failure)'))"
        else -> "[]"
    }

    fun helper(service: ArenaService): String = """
        const arenaNormalize = value => String(value || '').replace(/\s+/g, ' ').trim();
        const arenaUserText = row => {
          const body = row.querySelector('[class*=bg-g-send], .user-content__text, .segment-content') || row;
          const copy = body.cloneNode(true);
          copy.querySelectorAll('button, [role=button], [class*=actions], [class*=action-bar], img').forEach(n => n.remove());
          copy.querySelectorAll('br').forEach(n => n.replaceWith(document.createTextNode('\n')));
          copy.querySelectorAll('p, div, li, pre, blockquote').forEach(n => n.appendChild(document.createTextNode('\n')));
          return arenaNormalize(copy.textContent);
        };
        const arenaUserId = row => row.getAttribute('data-conversation-turn-id') || row.getAttribute('data-archer-id') || row.getAttribute('data-message-id') || row.querySelector('[data-message-id]')?.getAttribute('data-message-id') || row.id || '';
        const arenaFindRequestUser = () => {
          const users = ${users(service)};
          if (!state.expectedPrompt) return users.find(row => row.getAttribute('data-ai-arena-request') === requestId) || users.slice(Number(state.userBaseline || 0)).pop() || null;
          if (!state.submittedAt && !(window.__aiArenaSendClicks && window.__aiArenaSendClicks[requestId]) && !state.boundUserId && !state.bound) return null;
          const matches = users.filter(row => arenaUserText(row) === state.expectedPrompt);
          const bound = matches.find(row => row.getAttribute('data-ai-arena-request') === requestId || (state.boundUserId && arenaUserId(row) === state.boundUserId));
          if (bound) return bound;
          const fresh = matches.filter(row => row.getAttribute('data-ai-arena-before') !== requestId && !(state.beforeUserIds || []).includes(arenaUserId(row)));
          if (fresh.length === 1 && (arenaUserId(fresh[0]) || !(state.beforeUserTexts || []).includes(state.expectedPrompt))) return fresh[0];
          const before = state.beforeUserTexts || [];
          if (users.length === before.length + 1 && before.every((text, i) => arenaUserText(users[i]) === text)) {
            const last = users[users.length - 1];
            if (arenaUserText(last) === state.expectedPrompt) return last;
          }
          return null;
        };
        const arenaRecordSubmission = () => {
          state.submittedAt = Date.now();
          try { sessionStorage.setItem(cursorKey, JSON.stringify(state)); } catch (_) {}
          window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
          window.__aiArenaSendClicks[requestId] = state.submittedAt;
        };
    """.trimIndent()
}
