package com.tianlin.aiarena

internal object ArenaWebResponseScript {
    fun build(service: ArenaService, requestId: String): String {
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val serviceBody = when (service) {
            ArenaService.DEEPSEEK -> """
                const root = document.querySelector('.ds-virtual-list-visible-items');
                const items = root ? Array.from(root.children) : [];
                const tagged = Array.from(document.querySelectorAll('[data-ai-arena-request]')).find(function(row) {
                  return row.getAttribute('data-ai-arena-request') === requestId;
                });
                const user = tagged || items.filter(function(row) {
                  return !row.querySelector('.ds-markdown') && clean(row.innerText || row.textContent || '').length > 0;
                }).pop();
                let element = null;
                if (user && items.includes(user)) {
                  const index = items.indexOf(user);
                  const answerRow = items.slice(index + 1).find(function(row) { return !!row.querySelector('.ds-markdown'); });
                  element = answerRow && (answerRow.querySelector('.ds-markdown') || answerRow);
                }
                if (!element) {
                  const all = ['.ds-markdown', '[class*=assistant-message]', '[class*=bot-message]', '.markdown-body', '.prose'];
                  let candidates = [];
                  for (const selector of all) {
                    try { candidates = Array.from(document.querySelectorAll(selector)); } catch (_) { candidates = []; }
                    if (candidates.length) break;
                  }
                  const baseline = Number(state.assistantBaseline || 0);
                  if (baseline < candidates.length) {
                    element = candidates.slice(baseline).filter(function(row) {
                      return clean(row.innerText || row.textContent || '').length > 0;
                    }).pop() || null;
                  }
                }
                text = element ? clean(element.innerText || element.textContent || '') : '';
                streaming = !!document.querySelector('.ds-loading, [class*=generating], [class*=stop]');
            """.trimIndent()
            ArenaService.DOUBAO -> """
                const rows = Array.from(document.querySelectorAll('[class*=v_list_row][data-observe-row]'));
                const tagged = rows.find(function(row) {
                  return row.getAttribute('data-ai-arena-request') === requestId;
                });
                const userRows = rows.filter(function(row) { return !!row.querySelector('[class*=bg-g-send]'); });
                const user = tagged || userRows.slice(Number(state.userBaseline || 0)).pop() || null;
                if (user) {
                  const index = rows.indexOf(user);
                  const answerRow = rows.slice(index + 1).find(function(row) {
                    return !row.querySelector('[class*=bg-g-send]');
                  });
                  const element = answerRow && (answerRow.querySelector('.md-box-root') || answerRow);
                  text = element ? clean(element.innerText || element.textContent || '') : '';
                  streaming = !!answerRow && !answerRow.querySelector('[class*=message-action-bar]');
                }
            """.trimIndent()
            ArenaService.KIMI -> """
                const users = Array.from(document.querySelectorAll('.chat-content-item-user'));
                const tagged = users.find(function(row) {
                  return row.getAttribute('data-ai-arena-request') === requestId;
                });
                const user = tagged || users.slice(Number(state.userBaseline || 0)).pop() || null;
                if (user) {
                  let assistant = user.nextElementSibling;
                  while (assistant && !String(assistant.className).includes('chat-content-item-assistant')) {
                    assistant = assistant.nextElementSibling;
                  }
                  const finalBlocks = assistant ? Array.from(assistant.querySelectorAll('.markdown-container:not(.toolcall-content-text)')).filter(function(item) {
                    return !item.closest('[class*=toolcall], [class*=thinking], [class*=thought]');
                  }) : [];
                  const element = finalBlocks.filter(function(item) {
                    return clean(item.innerText || item.textContent || '').length > 0;
                  }).pop();
                  text = element ? clean(element.innerText || element.textContent || '') : '';
                  streaming = !!assistant && !assistant.querySelector('.segment-assistant-actions');
                }
            """.trimIndent()
            ArenaService.QWEN -> """
                securityChallenge = location.href.includes('/punish') ||
                  Array.from(document.querySelectorAll('iframe')).some(function(frame) {
                    const source = String(frame.src || '');
                    return source.includes('/punish') || source.includes('action=captcha') || source.includes('x5secdata=');
                  });
                let networkRecord = window.__aiArenaQwenResponses && window.__aiArenaQwenResponses[requestId];
                if (!networkRecord) {
                  try {
                    networkRecord = JSON.parse(sessionStorage.getItem('__ai_arena_qwen_response_' + requestId) || 'null');
                  } catch (_) { networkRecord = null; }
                }
                if (networkRecord && clean(networkRecord.answer).length > 0) {
                  text = clean(networkRecord.answer);
                  streaming = !networkRecord.done;
                }
                const all = ['[class*=qk-markdown]', '.qk-md-paragraph', '[class*=assistant] [class*=content]', '[class*=answer-content]'];
                let candidates = [];
                for (const selector of all) {
                  try { candidates = Array.from(document.querySelectorAll(selector)); } catch (_) { candidates = []; }
                  if (candidates.length) break;
                }
                const baseline = Number(state.assistantBaseline || 0);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = baseline < candidates.length ? candidates.slice(baseline) : [];
                const element = scoped.filter(function(row) {
                  return clean(row.innerText || row.textContent || '').length > 0;
                }).pop() || null;
                if (!text) text = element ? clean(element.innerText || element.textContent || '') : '';
                if (!networkRecord) {
                  streaming = !!document.querySelector('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=generating]');
                }
                if (securityChallenge && !text) {
                  throw new Error('千问触发安全验证，请打开千问网页完成验证后重试');
                }
            """.trimIndent()
            ArenaService.YUANBAO -> """
                const all = ['[class*=hyc-content-md]', '[class*=hyc-common-markdown]', '[class*=assistant] [class*=content]'];
                let candidates = [];
                for (const selector of all) {
                  try { candidates = Array.from(document.querySelectorAll(selector)); } catch (_) { candidates = []; }
                  if (candidates.length) break;
                }
                const baseline = Number(state.assistantBaseline || 0);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = baseline < candidates.length ? candidates.slice(baseline) : [];
                const element = scoped.filter(function(row) {
                  return clean(row.innerText || row.textContent || '').length > 0;
                }).pop() || null;
                text = element ? clean(element.innerText || element.textContent || '') : '';
                streaming = !!document.querySelector('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=generating]');
            """.trimIndent()
            ArenaService.ZHIPU -> """
                const all = ['[class*=assistant] [class*=markdown]', '[class*=assistant] [class*=content]', '[data-role=assistant]', '[class*=answer] [class*=markdown]', '[class*=markdown-body]'];
                let candidates = [];
                for (const selector of all) {
                  try { candidates = Array.from(document.querySelectorAll(selector)); } catch (_) { candidates = []; }
                  if (candidates.length) break;
                }
                const baseline = Number(state.assistantBaseline || 0);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = baseline < candidates.length ? candidates.slice(baseline) : [];
                const element = scoped.filter(function(row) {
                  return clean(row.innerText || row.textContent || '').length > 0;
                }).pop() || null;
                text = element ? clean(element.innerText || element.textContent || '') : '';
                streaming = !!document.querySelector('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=stop], [class*=generating], [class*=typing]');
            """.trimIndent()
        }
        return """
            (function() {
              $stateBootstrap
              const clean = function(value) { return String(value || '').trim(); };
              let text = '';
              let streaming = false;
              let securityChallenge = false;
              try {
                $serviceBody
                const originalLength = text.length;
                const truncated = originalLength > ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS};
                if (truncated) text = text.slice(0, ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS});
                return JSON.stringify({ found: text.length > 0, text, streaming, truncated, originalLength, securityChallenge });
              } catch (error) {
                return JSON.stringify({ found: false, text: '', streaming: false, truncated: false, originalLength: 0, securityChallenge, error: String(error && error.message || error) });
              }
            })();
        """.trimIndent()
    }
}
