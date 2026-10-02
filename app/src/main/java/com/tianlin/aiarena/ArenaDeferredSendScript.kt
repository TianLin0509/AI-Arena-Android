package com.tianlin.aiarena

/** A hydrated composer may replace its editor or enable submit after the first two probes. */
internal object ArenaDeferredSendScript {
    fun awaitFirstSend(inputSelectors: String, sendSelectors: String, conversationAdvanced: String, delayMillis: Int): String = """
        const readyUrl = location.href;
        const uniqueVisibleMatch = selectors => {
          for (const selector of selectors) {
            const nodes = Array.from(document.querySelectorAll(selector)).filter(node => {
              const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
              return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
            });
            if (nodes.length) return nodes.length === 1 ? nodes[0] : null;
          }
          return null;
        };
        const awaitFirstSend = function() {
          if (cancelled() || location.href !== readyUrl || window.__aiArenaRequests?.[requestId] !== state ||
              !arenaRequestScopeValid()) return;
          if (state.submittedAt || window.__aiArenaSendClicks?.[requestId] ||
              window.__aiArenaNativeSendRequests?.[requestId] || $conversationAdvanced) return;
          const retry = () => setTimeout(awaitFirstSend, 250);
          if (document.visibilityState !== 'visible') return retry();
          const editor = uniqueVisibleMatch($inputSelectors);
          if (!editor) return retry();
          const current = arenaNormalize(editor.value || editor.innerText || editor.textContent || '');
          if (current !== state.expectedPrompt) return retry();
          const send = uniqueVisibleMatch($sendSelectors);
          if (!arenaSendEnabled(send)) return retry();
          // Never rewrite a remounted editor, guess Enter, or retry a claimed click.
          arenaRecordSubmission();
          send.click();
        };
        if (!delayedInput) setTimeout(awaitFirstSend, $delayMillis);
    """.trimIndent()
}
