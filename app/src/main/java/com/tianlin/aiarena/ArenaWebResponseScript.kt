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
        /**
         * "消息操作栏已经真的显示出来"：豆包在生成期间就把 message-action-bar 渲染在 DOM 里，
         * 只是高度 0、里面没有按钮，回答结束才展开（2026-09-05 实测：只看存在与否会在 AI 停顿时
         * 提前判完成，把"老年人"三个字当成整条回答存下来）。
         */
        const barVisible = function(bar) {
          if (!bar) return false;
          try {
            const rect = bar.getBoundingClientRect();
            const style = window.getComputedStyle(bar);
            if (rect.height <= 2 || style.display === 'none' || style.visibility === 'hidden') return false;
            return bar.querySelectorAll('button, [role=button], svg').length > 0;
          } catch (_) { return false; }
        };
        /** 元素此刻真的画在屏幕上（有尺寸、没被 display/visibility 藏起来）。 */
        const isVisible = function(el) {
          if (!el) return false;
          try {
            const rect = el.getBoundingClientRect();
            const style = window.getComputedStyle(el);
            return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden';
          } catch (_) { return false; }
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
                // 2026-09 的 DeepSeek 页面：深度思考的内容也是 .ds-markdown，只是包在 .ds-think-content 里；
                // 正式回答带 .ds-assistant-message-main-content。原来取行内第一个 .ds-markdown，
                // 开了深度思考就永远抓到思考过程，真正的回答一个字都没存（用户反馈 2026-09-05）。
                let answerRow = null;
                if (user && items.includes(user)) {
                  const index = items.indexOf(user);
                  answerRow = items.slice(index + 1).find(function(row) { return !!row.querySelector('.ds-markdown'); }) || null;
                }
                if (!answerRow) {
                  const picked = pickSelector(['.ds-markdown', '[class*=assistant-message]', '[class*=bot-message]', '.markdown-body', '.prose']);
                  const scoped = scopeAfterTag(picked.nodes, tagged || null, Number(state.assistantBaseline || 0));
                  const lastNode = scoped.nodes.filter(function(row) {
                    return clean(row.innerText || row.textContent || '').length > 0;
                  }).pop() || null;
                  answerRow = lastNode ? (lastNode.closest('.ds-message') || lastNode.parentElement || lastNode) : null;
                }
                if (answerRow) {
                  const mains = Array.from(answerRow.querySelectorAll('.ds-markdown.ds-assistant-message-main-content'));
                  const nonThink = Array.from(answerRow.querySelectorAll('.ds-markdown')).filter(function(node) {
                    return !node.closest('.ds-think-content, [class*=think]');
                  });
                  const finalNodes = mains.length ? mains : topLevelOnly(nonThink);
                  finalText = clean(finalNodes.map(function(node) { return arenaToMarkdown(node); })
                    .filter(function(part) { return part.length > 0; }).join('\n\n'));
                  // 进度文本：正式回答还没开始时拿思考过程凑数，让人知道它在动
                  const anyMarkdown = Array.from(answerRow.querySelectorAll('.ds-markdown'));
                  text = finalText || (anyMarkdown.length ? arenaToMarkdown(anyMarkdown[anyMarkdown.length - 1]) : '');
                  // 回答结束的明确信号：这条消息下面出现了复制 / 重新生成那排操作按钮
                  // 操作栏容器就是这些图标按钮的父节点；不用 :has()，老 WebView 不认，一抛错整段都读不到
                  const actionButton = answerRow.querySelector('.ds-button--iconLabelTertiary, .ds-button--icon');
                  const actionBar = !!actionButton && barVisible(actionButton.parentElement || actionButton);
                  const stopButton = !!document.querySelector('.ds-loading, [class*=generating], button[class*=stop]');
                  streaming = stopButton || !actionBar;
                }
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
                  // 深度思考 / 联网搜索会多出别的容器，正式回答只认不在思考容器里的 .md-box-root，多块按顺序拼
                  const boxes = answerRow ? Array.from(answerRow.querySelectorAll('.md-box-root')) : [];
                  const answerBoxes = topLevelOnly(boxes.filter(function(box) {
                    return !box.closest('[class*=think], [class*=thought], [class*=reason], [class*=search-result]');
                  }));
                  finalText = clean(answerBoxes.map(function(box) { return arenaToMarkdown(box); })
                    .filter(function(part) { return part.length > 0; }).join('\n\n'));
                  const progressNode = boxes.length ? boxes[boxes.length - 1] : answerRow;
                  text = finalText || (progressNode ? arenaToMarkdown(progressNode) : '');
                  streaming = !!answerRow && !barVisible(answerRow.querySelector('[class*=message-action-bar]'));
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
                  finalText = clean(parts.join('\n\n'));
                  // 正式回答还没开始（还在思考）时，用最后一个 markdown 块当进度文本
                  const anyBlocks = assistant ? Array.from(assistant.querySelectorAll('.markdown-container')) : [];
                  text = finalText || (anyBlocks.length ? arenaToMarkdown(anyBlocks[anyBlocks.length - 1]) : '');
                  streaming = !!assistant && !barVisible(assistant.querySelector('.segment-assistant-actions'));
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
                  weakDoneSignal = true;
                }
                if (securityChallenge && !text) {
                  throw new Error('千问触发安全验证，请打开千问网页完成验证后重试');
                }
            """.trimIndent()
            ArenaService.YUANBAO -> """
                // 2026-09 的元宝页面：一条回答 = .agent-chat__list__item--ai 里的 .agent-chat__conv--ai__speech_show。
                // 原来按内部 hyc-content-md 小块拼接，加粗片段各自成段（真机实测 235 字被拆成 7 段）；改为整块转换。
                const picked = pickSelector(['.agent-chat__conv--ai__speech_show', '[class*=hyc-content-md]', '[class*=hyc-common-markdown]', '[class*=assistant] [class*=content]']);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = scopeAfterTag(picked.nodes, tagged, Number(state.assistantBaseline || 0));
                text = collectText(scoped, [], picked.selector, scoped.anchored);
                // 深度搜索 / 思考过程 / 来源列表不算正式回答：在副本上摘掉再转 Markdown，页面本身不动
                const auxYuanbao = '[class*=think], [class*=thought], [class*=reason], [class*=process], [class*=deep-search], [class*=timeline], [class*=sources], [class*=search-result]';
                const stripAux = function(node) {
                  try {
                    const clone = node.cloneNode(true);
                    Array.from(clone.querySelectorAll(auxYuanbao)).forEach(function(el) { if (el.parentNode) el.parentNode.removeChild(el); });
                    return clone;
                  } catch (_) { return node; }
                };
                const speechNodes = scoped.nodes.filter(function(node) {
                  try { return node.matches('.agent-chat__conv--ai__speech_show'); } catch (_) { return false; }
                });
                if (speechNodes.length) {
                  const speech = speechNodes[speechNodes.length - 1];
                  // 开头那行"已处理 / 已完成 / 已搜索…"是状态标签，不是回答
                  const dropStatus = function(value) {
                    return clean(String(value || '').replace(/^(已处理|已完成|已搜索[^\n]*|已思考[^\n]*|已联网搜索[^\n]*)\s*\n+/, ''));
                  };
                  finalText = dropStatus(arenaToMarkdown(stripAux(speech)));
                  text = finalText || dropStatus(arenaToMarkdown(speech)) || text;
                }
                // 结束信号（采样验证）：生成期间输入区有 "Stop Answering" 控件、该条回答的 toolbar 隐藏；结束后反过来
                const aiItems = Array.from(document.querySelectorAll('.agent-chat__list__item--ai'));
                const lastItem = aiItems.length ? aiItems[aiItems.length - 1] : null;
                const stopVisible = Array.from(document.querySelectorAll('[aria-label="Stop Answering"], [aria-label*="停止"], button[class*=stop]')).some(isVisible);
                const toolbarVisible = !!lastItem && isVisible(lastItem.querySelector('.agent-chat__conv--ai__toolbar'));
                streaming = stopVisible || (!!lastItem && !toolbarVisible);
            """.trimIndent()
            ArenaService.ZHIPU -> """
                // 2026-09 的智谱页面：一条回答 = .answer 里的 .answer-content（前面的"AI生成"标签不在其中）
                const picked = pickSelector(['.answer .answer-content', '[class*=assistant] [class*=markdown]', '[class*=assistant] [class*=content]', '[data-role=assistant]', '[class*=answer] [class*=markdown]', '[class*=markdown-body]']);
                const tagged = document.querySelector('[data-ai-arena-request="' + requestId + '"]');
                const scoped = scopeAfterTag(picked.nodes, tagged, Number(state.assistantBaseline || 0));
                text = collectText(scoped, [], picked.selector, scoped.anchored);
                // 结束信号：回答下面的 .interact 操作区显示出来；生成期间若有停止按钮也算还在生成
                const answers = Array.from(document.querySelectorAll('.answer'));
                const lastAnswer = answers.length ? answers[answers.length - 1] : null;
                const stopVisible = Array.from(document.querySelectorAll('button[class*=stop], button[aria-label*=停止], button[aria-label*=Stop], [class*=generating], [class*=typing]')).some(isVisible);
                const interactVisible = !!lastAnswer && isVisible(lastAnswer.querySelector('.interact'));
                streaming = stopVisible || (!!lastAnswer && !interactVisible);
                // .interact 的时序没在真机上采样过，先按弱信号多等两轮
                weakDoneSignal = true;
            """.trimIndent()
        }
        return """
            (function() {
              $stateBootstrap
              const clean = function(value) { return String(value || '').trim(); };
              ${ArenaMarkdownScript.helper}
              $scopeHelper
              let text = '';
              // finalText：严格抓取的正式回答（排除思考过程），回答结束后以它为准；留空表示与 text 相同
              let finalText = '';
              let streaming = false;
              // 站点只给得出"停止按钮"这种弱信号时置 true，控制器会多等两轮再判完成
              let weakDoneSignal = false;
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
                if (finalText.length > ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS}) {
                  let cutFinal = ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS};
                  const unit = finalText.charCodeAt(cutFinal - 1);
                  if (unit >= 0xD800 && unit <= 0xDBFF) cutFinal -= 1;
                  finalText = finalText.slice(0, cutFinal);
                }
                return JSON.stringify({ found: text.length > 0, text, finalText: finalText || text, streaming, weakDoneSignal, truncated, originalLength, securityChallenge });
              } catch (error) {
                return JSON.stringify({ found: false, text: '', streaming: false, truncated: false, originalLength: 0, securityChallenge, error: String(error && error.message || error) });
              }
            })();
        """.trimIndent()
    }
}
