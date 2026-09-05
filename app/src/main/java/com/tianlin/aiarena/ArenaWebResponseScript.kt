package com.tianlin.aiarena

internal object ArenaWebResponseScript {
    /**
     * 用发送时打在用户消息上的 `data-ai-arena-request` 标记来限定答案范围。
     *
     * 原来只用「选择器命中数量」当基线（`candidates.slice(baseline)`），在做了虚拟列表
     * 的站点上不可靠：旧消息被回收后命中数会变少，`slice` 直接返回空数组，于是永远
     * 读不到答案，只能等 5 分钟超时。有标记锚点时按文档顺序取"排在标记之后"的节点，
     * 拿不到标记再退回原来的计数基线。
     */
    private val scopeHelper = """
        const scopeAfterTag = function(candidates, tagged, baseline) {
          if (tagged) {
            const after = candidates.filter(function(row) {
              try {
                return !!(tagged.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING);
              } catch (_) { return false; }
            });
            if (after.length) return { nodes: after, anchored: true };
          }
          const fallback = baseline < candidates.length ? candidates.slice(baseline) : [];
          return { nodes: fallback, anchored: false };
        };
        const pickSelector = function(selectors) {
          for (const selector of selectors) {
            try {
              const found = Array.from(document.querySelectorAll(selector));
              if (found.length) return { nodes: found, selector: selector };
            } catch (_) {}
          }
          return { nodes: [], selector: '' };
        };
        /**
         * 思考过程 / 工具调用 / 联网搜索结果这些辅助区块不算正式回答。
         * 以前只取最后一个节点时它们天然被排除掉，改成整段拼接后必须显式过滤，
         * 否则「深度思考」的内容会被当成答案的一部分抓回来。
         * 万一过滤把候选清空了（类名撞车），退回不过滤的原集合，宁可多抓也不要抓空。
         */
        const auxiliaryPattern = '[class*=think], [class*=thought], [class*=reason], [class*=toolcall], [class*=tool-call], [class*=tool_call], [class*=search-result]';
        const withoutAuxiliary = function(nodes) {
          const kept = nodes.filter(function(node) {
            try { return !(node.closest && node.closest(auxiliaryPattern)); } catch (_) { return true; }
          });
          return kept.length ? kept : nodes;
        };
        /** 去掉被别的候选包住的节点，避免同一段内容被算两遍。 */
        const topLevelOnly = function(nodes) {
          return nodes.filter(function(node) {
            return !nodes.some(function(other) {
              return other !== node && other.contains && other.contains(node);
            });
          });
        };
        const collectText = function(scoped, fragmentSelectors, selector, anchored) {
          const filled = scoped.nodes.filter(function(row) {
            return clean(row.innerText || row.textContent || '').length > 0;
          });
          if (!filled.length) return '';
          // 锚定成功时 scoped 里的节点已确定全部排在本轮那条用户消息之后，整段拼接是安全的。
          // 此前还额外要求选择器出现在 fragmentSelectors 白名单里，而元宝和智谱传的是空数组，
          // 于是它们永远只取最后一个节点 —— 站点把一条回答拆成多个 markdown 容器时，
          // 就只能抓到结尾那一小段。先剔除互相嵌套的候选，再按文档顺序拼。
          if (anchored) {
            const parts = topLevelOnly(withoutAuxiliary(filled)).map(function(row) {
              return arenaToMarkdown(row);
            }).filter(function(part) { return part.length > 0; });
            if (parts.length) return clean(parts.join('\n\n'));
          }
          // 没有锚点时范围只是猜的，仍旧退回"取最后一个"，避免把上一轮的回答拼进来。
          return arenaToMarkdown(filled[filled.length - 1]);
        };
    """.trimIndent()

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
                  const picked = pickSelector(['.ds-markdown', '[class*=assistant-message]', '[class*=bot-message]', '.markdown-body', '.prose']);
                  const scoped = scopeAfterTag(picked.nodes, tagged || null, Number(state.assistantBaseline || 0));
                  element = scoped.nodes.filter(function(row) {
                    return clean(row.innerText || row.textContent || '').length > 0;
                  }).pop() || null;
                }
                text = element ? arenaToMarkdown(element) : '';
                streaming = !!document.querySelector('.ds-loading, [class*=generating], button[class*=stop]');
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
                  text = element ? arenaToMarkdown(element) : '';
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
                  // 2026-09 起 Kimi 把整条回答（含思考过程）都包在 .toolcall-rollup 里，
                  // 原来按 [class*=toolcall] 一刀切会把真正的答案也排掉，抓不到一个字。
                  // 思考块的特征是 .toolcall-container.thinking-container / .toolcall-flow / .toolcall-content，
                  // 只排这些，rollup 外壳不算。
                  const finalBlocks = assistant ? Array.from(assistant.querySelectorAll('.markdown-container')).filter(function(item) {
                    if (item.classList.contains('toolcall-content-text')) return false;
                    return !item.closest('.toolcall-container, .thinking-container, .toolcall-flow, .toolcall-content, [class*=thinking], [class*=thought]');
                  }) : [];
                  // finalBlocks 已经限定在本轮那条 assistant 节点内部，整段拼接是安全的。
                  // 原来这里 .pop() 只取最后一块，Kimi 把回答拆成多个 markdown-container 时
                  // 就只剩结尾一小段。
                  const blocks = finalBlocks.filter(function(item) {
                    return clean(item.innerText || item.textContent || '').length > 0;
                  });
                  const parts = blocks.map(function(item) {
                    return arenaToMarkdown(item);
                  }).filter(function(part) { return part.length > 0; });
                  text = parts.join('\n\n');
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
                const networkAnswer = networkRecord ? clean(networkRecord.answer) : '';
                if (networkAnswer.length > 0) {
                  text = networkAnswer;
                  streaming = !networkRecord.done;
                }
                const picked = pickSelector(['[class*=qk-markdown]', '.qk-md-paragraph', '[class*=assistant] [class*=content]', '[class*=answer-content]']);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = scopeAfterTag(picked.nodes, tagged, Number(state.assistantBaseline || 0));
                if (!text) text = collectText(scoped, ['.qk-md-paragraph'], picked.selector, scoped.anchored);
                // SSE 已经建了 record 但还没解析出内容时，networkRecord 存在而 answer 为空。
                // 此时 streaming 必须回落到 DOM 探测，否则会被当成"已经稳定"提前判完成，
                // 把千问思考过程中的半截答案当作最终答案存下来。
                if (networkAnswer.length === 0) {
                  streaming = !!document.querySelector('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=generating]');
                }
                if (securityChallenge && !text) {
                  throw new Error('千问触发安全验证，请打开千问网页完成验证后重试');
                }
            """.trimIndent()
            ArenaService.YUANBAO -> """
                const picked = pickSelector(['[class*=hyc-content-md]', '[class*=hyc-common-markdown]', '[class*=assistant] [class*=content]']);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = scopeAfterTag(picked.nodes, tagged, Number(state.assistantBaseline || 0));
                text = collectText(scoped, [], picked.selector, scoped.anchored);
                streaming = !!document.querySelector('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=generating]');
            """.trimIndent()
            ArenaService.ZHIPU -> """
                const picked = pickSelector(['[class*=assistant] [class*=markdown]', '[class*=assistant] [class*=content]', '[data-role=assistant]', '[class*=answer] [class*=markdown]', '[class*=markdown-body]']);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = scopeAfterTag(picked.nodes, tagged, Number(state.assistantBaseline || 0));
                text = collectText(scoped, [], picked.selector, scoped.anchored);
                streaming = !!document.querySelector('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=generating], [class*=typing]');
            """.trimIndent()
        }
        return """
            (function() {
              $stateBootstrap
              const clean = function(value) { return String(value || '').trim(); };
              ${ArenaMarkdownScript.helper}
              $scopeHelper
              let text = '';
              let streaming = false;
              let securityChallenge = false;
              try {
                $serviceBody
                const originalLength = text.length;
                let truncated = originalLength > ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS};
                if (truncated) {
                  let cut = ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS};
                  // 不要从代理对（emoji 等）中间切开，否则转成 UTF-8 会变成问号。
                  const codeUnit = text.charCodeAt(cut - 1);
                  if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF) cut -= 1;
                  text = text.slice(0, cut);
                }
                return JSON.stringify({ found: text.length > 0, text, streaming, truncated, originalLength, securityChallenge });
              } catch (error) {
                return JSON.stringify({ found: false, text: '', streaming: false, truncated: false, originalLength: 0, securityChallenge, error: String(error && error.message || error) });
              }
            })();
        """.trimIndent()
    }
}
