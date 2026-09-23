package com.tianlin.aiarena

/** Provider message identity shared by submission acknowledgement and answer extraction. */
internal object ArenaWebMessageIdentity {
    const val scopeChangedDetail = "DeepSeek 页面或会话已变化，本轮自动处理已停止，请打开原网页核对；不会自动重复发送"
    fun supported(service: ArenaService) = service == ArenaService.DEEPSEEK || service == ArenaService.DOUBAO || service == ArenaService.KIMI

    // Doubao replaced v_list_row with message-box-target-id wrappers in September 2026.
    const val doubaoRows = "Array.from(document.querySelectorAll('[data-target-id=message-box-target-id], [class*=v_list_row][data-observe-row]')).filter(row => !row.parentElement?.closest('[data-target-id=message-box-target-id], [class*=v_list_row][data-observe-row]'))"

    fun users(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> "Array.from(document.querySelector('.ds-virtual-list-visible-items')?.children || []).filter(row => { const body = row.querySelector('.ds-message .ds-collapsible-text'); return !!body && !body.closest('.ds-markdown, .ds-think-content'); })"
        ArenaService.DOUBAO -> "$doubaoRows.filter(row => !!row.querySelector('[data-send-message-boundary], [class*=bg-g-send]'))"
        ArenaService.KIMI -> "Array.from(document.querySelectorAll('.chat-content-item-user:not(.awaiting-failure)'))"
        else -> "[]"
    }

    fun helper(service: ArenaService): String = """
        const arenaNormalize = value => String(value || '').replace(/\s+/g, ' ').trim();
        ${if (service == ArenaService.DOUBAO) doubaoRawTextHelper else ""}
        const arenaUserText = row => {
          ${if (service == ArenaService.DOUBAO) "const raw = arenaDoubaoRawText(row); if (raw.present) return raw.valid ? arenaNormalize(raw.text) : '';" else ""}
          const body = row.querySelector('${if (service == ArenaService.DEEPSEEK) ".ds-message .ds-collapsible-text" else "[class*=bg-g-send], .user-content__text, .segment-content"}') || row;
          const copy = body.cloneNode(true);
          copy.querySelectorAll('button, [role=button], [class*=actions], [class*=action-bar], img').forEach(n => n.remove());
          copy.querySelectorAll('br').forEach(n => n.replaceWith(document.createTextNode('\n')));
          copy.querySelectorAll('p, div, li, pre, blockquote').forEach(n => n.appendChild(document.createTextNode('\n')));
          return arenaNormalize(copy.textContent);
        };
        const arenaUserId = row => ${if (service == ArenaService.DEEPSEEK) "row.getAttribute('data-virtual-list-item-key') || ''" else "row.getAttribute('data-conversation-turn-id') || row.getAttribute('data-archer-id') || row.getAttribute('data-message-id') || row.querySelector('[data-message-id]')?.getAttribute('data-message-id') || row.id || ''"};
        const arenaMatchesRequestUser = row => {
          ${if (service == ArenaService.DOUBAO) """
          const raw = arenaDoubaoRawText(row);
          if (raw.present) return raw.valid && typeof state.expectedRawPrompt === 'string' &&
            arenaNormalize(raw.text) === state.expectedPrompt && raw.text.replace(/\r\n/g, '\n').trim() === state.expectedRawPrompt;
          """.trimIndent() else ""}
          return arenaUserText(row) === state.expectedPrompt;
        };
        const arenaRequestScopeValid = () => {
          ${if (service == ArenaService.DEEPSEEK) """
          if (state.expectedPrompt && state.legacyAttachment !== true) {
            const initial = new URL(state.initialUrl);
            const currentUrl = location.origin + location.pathname;
            const initialUrl = initial.origin + initial.pathname;
            const sessionPath = /^\/a\/chat\/s\/[^/]+\/?${'$'}/;
            const validDocument = state.documentToken && state.documentToken === window.__aiArenaDeepSeekDocument;
            const validUrl = state.boundConversationUrl ? state.boundConversationUrl === currentUrl :
              (currentUrl === initialUrl || (initial.pathname === '/' && !!state.submittedAt && location.origin === initial.origin && sessionPath.test(location.pathname)));
            if (state.scopeFailure || !validDocument || !validUrl) {
              state.scopeFailure = true;
              try { sessionStorage.setItem(cursorKey, JSON.stringify(state)); } catch (_) {}
              return false;
            }
          }
          """.trimIndent() else ""}
          return true;
        };
        const arenaFindRequestUser = () => {
          if (!arenaRequestScopeValid()) return null;
          ${if (service == ArenaService.DEEPSEEK) "if (state.expectedPrompt && state.legacyAttachment !== true && !/^\\/a\\/chat\\/s\\/[^/]+\\/?${'$'}/.test(location.pathname)) return null;" else ""}
          const users = ${users(service)};
          if (!state.expectedPrompt) return users.find(row => row.getAttribute('data-ai-arena-request') === requestId) || users.slice(Number(state.userBaseline || 0)).pop() || null;
          if (!state.submittedAt && !(window.__aiArenaSendClicks && window.__aiArenaSendClicks[requestId]) && !state.boundUserId && !state.bound) return null;
          const matches = users.filter(arenaMatchesRequestUser);
          // Once the website gives this request an identity, never drift to a later
          // identical question or a copied DOM tag while its row is temporarily absent.
          if (state.boundUserId) {
            const pinned = matches.filter(row => arenaUserId(row) === state.boundUserId);
            return pinned.length === 1 ? pinned[0] : null;
          }
          if (state.bound) {
            const original = window.__aiArenaBoundUsers && window.__aiArenaBoundUsers[requestId];
            return original && matches.includes(original) ? original : null;
          }
          const tagged = matches.filter(row => row.getAttribute('data-ai-arena-request') === requestId);
          if (tagged.length === 1) return tagged[0];
          if (tagged.length > 1) return null;
          const fresh = matches.filter(row => row.getAttribute('data-ai-arena-before') !== requestId && !(state.beforeUserIds || []).includes(arenaUserId(row)));
          if (fresh.length === 1 && (arenaUserId(fresh[0]) || !(state.beforeUserTexts || []).includes(state.expectedPrompt))) return fresh[0];
          const before = state.beforeUserTexts || [];
          if (users.length === before.length + 1 && before.every((text, i) => arenaUserText(users[i]) === text)) {
            const last = users[users.length - 1];
            if (arenaMatchesRequestUser(last)) return last;
          }
          return null;
        };
        const arenaBindRequestUser = user => {
          user.setAttribute('data-ai-arena-request', requestId);
          state.bound = true;
          window.__aiArenaBoundUsers = window.__aiArenaBoundUsers || {};
          window.__aiArenaBoundUsers[requestId] = user;
          const id = arenaUserId(user);
          if (id) state.boundUserId = id;
          ${if (service == ArenaService.DEEPSEEK) "if (state.expectedPrompt) state.boundConversationUrl = location.origin + location.pathname;" else ""}
          window.__aiArenaRequests = window.__aiArenaRequests || {};
          window.__aiArenaRequests[requestId] = state;
          try { sessionStorage.setItem(cursorKey, JSON.stringify(state)); } catch (_) {}
        };
        const arenaRecordSubmission = () => {
          state.submittedAt = Date.now();
          try { sessionStorage.setItem(cursorKey, JSON.stringify(state)); } catch (_) {}
          window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
          window.__aiArenaSendClicks[requestId] = state.submittedAt;
        };
    """.trimIndent()

    private val doubaoRawTextHelper: String get() = """
        ${ArenaAttachmentScript.reactHelpers}
        const arenaDoubaoRawCache = new Map();
        const arenaDoubaoRawText = row => {
          if (arenaDoubaoRawCache.has(row)) return arenaDoubaoRawCache.get(row);
          const save = value => { arenaDoubaoRawCache.set(row, value); return value; };
          const boundaries = row.querySelectorAll('[data-send-message-boundary]');
          if (!boundaries.length) return save({present:false});
          if (boundaries.length !== 1) return save({present:true,valid:false});
          const boundary = boundaries[0];
          if (!Object.keys(boundary).some(key => key.startsWith('__reactFiber${'$'}'))) {
            // Even visually plain Markdown can have decoded entities or escapes.
            // A modern bubble without its original message is not a raw receipt.
            return save({present:true,valid:false});
          }
          // Only read committed React props owned by this exact DOM row. The attached
          // fiber can point at the stale alternate after a render or shared bailout.
          const path = currentReactPath(boundary);
          const ownerEnd = path ? path.findIndex(fiber => fiber.stateNode === row) : -1;
          if (ownerEnd < 0) return save({present:true,valid:false});
          const id = arenaUserId(row);
          if (!id) return save({present:true,valid:false});
          const messages = [];
          for (const fiber of path.slice(0, ownerEnd)) {
            const props = fiber.memoizedProps;
            for (const message of [props && props.message, props && props.value && props.value.message]) {
              if (message && !messages.includes(message)) messages.push(message);
            }
          }
          if (!messages.length) return save({present:true,valid:false});
          let text = null;
          for (const message of messages) {
            // Loaded messages expose a v1 projection as well as v2. A live follow-up
            // can expose only v2, even after its official ID and answer have arrived.
            // Every present schema must be valid and agree; never hide a malformed
            // or conflicting source behind the other schema, TTS or rendered text.
            if (message.message_id !== id) return save({present:true,valid:false});
            let sources = 0;
            for (const field of ['content_blocks', 'content_blocks_v2']) {
              const blocks = message[field];
              if (blocks === undefined) continue;
              if (!Array.isArray(blocks) || blocks.length !== 1) return save({present:true,valid:false});
              const block = blocks[0];
              if (!block || block.block_type !== 10000 || block.is_deleted === true ||
                  ['is_deleted', 'is_finish'].some(flag => block[flag] !== undefined && typeof block[flag] !== 'boolean')) return save({present:true,valid:false});
              const original = field === 'content_blocks' ? block.content_obj : block.content && block.content.text_block;
              if (!original || typeof original.text !== 'string') return save({present:true,valid:false});
              if (text !== null && text !== original.text) return save({present:true,valid:false});
              text = original.text;
              sources++;
            }
            if (!sources) return save({present:true,valid:false});
          }
          return save({present:true,valid:true,text});
        };
    """.trimIndent()
}
