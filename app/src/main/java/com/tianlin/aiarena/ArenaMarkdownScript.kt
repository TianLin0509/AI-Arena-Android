package com.tianlin.aiarena

/**
 * 注入网页的 DOM → Markdown 序列化器。
 *
 * 此前答案一律走 `element.innerText`，等于在离开网页的那一刻就把结构拍平了：
 * 加粗、标题、代码块、表格全没了，有序列表的序号因为是 `::marker` 伪元素
 * 干脆不在 innerText 里，引用角标则退化成混在正文中的裸数字（"…（Recursive
 * Self-Improvement）1 4 8 。"）。丢掉的结构不只影响观感 —— 这段文本还会原样
 * 喂给「观点讨论」和「讨论总结」的 prompt。
 *
 * 解析与渲染侧见 [ArenaMarkdown]。
 *
 * 约定：本文件的 JS 里**不允许出现裸 `${'$'}`**，否则会被 Kotlin 的字符串模板吃掉；
 * 需要"串尾"锚点时用 `(?![\s\S])`。
 */
internal object ArenaMarkdownScript {

    /** 定义 `arenaToMarkdown(node)`，抓取脚本在取文本前先注入它。 */
    val helper: String = """
        const arenaSup = { '0':'\u2070','1':'\u00B9','2':'\u00B2','3':'\u00B3','4':'\u2074',
                           '5':'\u2075','6':'\u2076','7':'\u2077','8':'\u2078','9':'\u2079' };
        const arenaSkipTags = { SCRIPT:1, STYLE:1, NOSCRIPT:1, SVG:1, BUTTON:1, SELECT:1,
                                TEXTAREA:1, IFRAME:1, CANVAS:1, VIDEO:1, AUDIO:1, FORM:1, HEAD:1 };
        const arenaBlockTags = /^(P|H1|H2|H3|H4|H5|H6|UL|OL|PRE|BLOCKQUOTE|HR|TABLE|DIV|SECTION|ARTICLE|FIGURE|DL|MAIN)(?![\s\S])/;
        const arenaSkipInline = /^(UL|OL|PRE|TABLE|BLOCKQUOTE)(?![\s\S])/;
        const arenaCiteClass = /(^|[\s_-])(cite|ref|footnote|citation|reference|superscript)/i;

        const arenaClassOf = function(el) {
          const value = el && el.className;
          if (typeof value === 'string') return value;
          if (value && typeof value.baseVal === 'string') return value.baseVal;
          return '';
        };

        /**
         * 引用角标判定。刻意不写死某一家的类名（我没在真机上 dump 过 DeepSeek 的角标类名，
         * 不想凭空猜一个写进代码），改用一组通用信号：
         * 必要条件是「几乎是叶子节点」+「整段文本是 1-3 位纯数字」——正文里的数字是文本节点的
         * 一部分，不会单独包一层元素，所以这条本身就过滤掉了绝大多数误判；
         * 再叠加 sup 标签 / 类名含 cite 词根 / 指向 #cite 的链接 / vertical-align 是上下标 /
         * 字号明显小于父元素 中的任意一条。
         */
        const arenaIsCitation = function(el) {
          if (!el || el.nodeType !== 1) return false;
          if (el.querySelectorAll && el.querySelectorAll('*').length > 1) return false;
          const txt = (el.textContent || '').trim();
          if (!/^[0-9]{1,3}(?![\s\S])/.test(txt)) return false;
          if (el.tagName === 'SUP') return true;
          if (arenaCiteClass.test(arenaClassOf(el))) return true;
          if (el.tagName === 'A' && /^#(cite|ref|fn)/i.test(el.getAttribute('href') || '')) return true;
          try {
            const own = window.getComputedStyle(el);
            if (own.verticalAlign === 'super' || own.verticalAlign === 'sub') return true;
            const host = el.parentElement ? window.getComputedStyle(el.parentElement) : null;
            if (host) {
              const mine = parseFloat(own.fontSize);
              const theirs = parseFloat(host.fontSize);
              if (mine > 0 && theirs > 0 && mine < theirs * 0.92) return true;
            }
          } catch (_) {}
          return false;
        };

        const arenaSuperscript = function(digits) {
          return String(digits).replace(/[0-9]/g, function(d) { return arenaSup[d] || d; });
        };

        const arenaEsc = function(value) {
          return String(value).replace(/([\\`*~\[\]])/g, function(m) { return '\\' + m; });
        };

        const arenaRtrim = function(value) {
          return String(value).replace(/\s+(?![\s\S])/, '');
        };

        const arenaHidden = function(el) {
          if (!el.getAttribute) return false;
          if (el.getAttribute('aria-hidden') === 'true') return true;
          if (el.hasAttribute && el.hasAttribute('hidden')) return true;
          return false;
        };

        /** 行内内容 → Markdown。遇到块级标签直接跳过，交给 arenaSerialize 处理。 */
        const arenaInline = function(node) {
          let out = '';
          const kids = node.childNodes || [];
          for (let i = 0; i < kids.length; i++) {
            const child = kids[i];
            if (child.nodeType === 3) {
              out += arenaEsc(String(child.nodeValue || '').replace(/\s+/g, ' '));
              continue;
            }
            if (child.nodeType !== 1) continue;
            const tag = child.tagName;
            if (arenaSkipTags[tag] || arenaHidden(child) || arenaSkipInline.test(tag)) continue;
            if (arenaIsCitation(child)) {
              out += arenaSuperscript((child.textContent || '').trim());
              continue;
            }
            if (tag === 'BR') { out += '\n'; continue; }
            if (tag === 'CODE') {
              const raw = (child.textContent || '').trim();
              if (raw) out += '`' + raw.replace(/`/g, '') + '`';
              continue;
            }
            if (tag === 'STRONG' || tag === 'B') {
              const inner = arenaInline(child).trim();
              if (inner) out += '**' + inner + '**';
              continue;
            }
            if (tag === 'EM' || tag === 'I') {
              const inner = arenaInline(child).trim();
              if (inner) out += '*' + inner + '*';
              continue;
            }
            if (tag === 'DEL' || tag === 'S' || tag === 'STRIKE') {
              const inner = arenaInline(child).trim();
              if (inner) out += '~~' + inner + '~~';
              continue;
            }
            if (tag === 'A') {
              const inner = arenaInline(child).trim();
              if (!inner) continue;
              const href = child.getAttribute('href') || '';
              out += /^https?:/i.test(href) ? ('[' + inner + '](' + href + ')') : inner;
              continue;
            }
            if (tag === 'IMG') continue;
            out += arenaInline(child);
          }
          return out;
        };

        const arenaHasBlockChild = function(el) {
          const kids = el.children || [];
          for (let i = 0; i < kids.length; i++) {
            if (arenaBlockTags.test(kids[i].tagName)) return true;
          }
          return false;
        };

        const arenaIndent = function(depth) {
          let pad = '';
          for (let i = 0; i < depth; i++) pad += '  ';
          return pad;
        };

        const arenaChildren = function(node, depth) {
          let out = '';
          const kids = node.childNodes || [];
          for (let i = 0; i < kids.length; i++) out += arenaSerialize(kids[i], depth);
          return out;
        };

        const arenaCells = function(row) {
          const cells = [];
          const kids = row.children || [];
          for (let i = 0; i < kids.length; i++) {
            const tag = kids[i].tagName;
            if (tag !== 'TD' && tag !== 'TH') continue;
            cells.push(arenaInline(kids[i]).replace(/\n/g, ' ').replace(/\|/g, '\\|').trim());
          }
          return cells;
        };

        const arenaSerialize = function(node, depth) {
          if (!node) return '';
          if (node.nodeType === 3) {
            const text = String(node.nodeValue || '').replace(/\s+/g, ' ').trim();
            return text ? arenaEsc(text) + '\n\n' : '';
          }
          if (node.nodeType !== 1) return '';
          const tag = node.tagName;
          if (arenaSkipTags[tag] || arenaHidden(node)) return '';

          if (/^H[1-6](?![\s\S])/.test(tag)) {
            const text = arenaInline(node).trim();
            if (!text) return '';
            let hashes = '';
            const level = Number(tag.charAt(1));
            for (let i = 0; i < level; i++) hashes += '#';
            return hashes + ' ' + text + '\n\n';
          }

          if (tag === 'HR') return '---\n\n';

          if (tag === 'PRE') {
            const holder = node.querySelector ? (node.querySelector('code') || node) : node;
            const classes = arenaClassOf(holder) + ' ' + arenaClassOf(node);
            const matched = classes.match(/language-([A-Za-z0-9+#._-]+)/);
            const body = arenaRtrim(String(holder.innerText || holder.textContent || ''));
            if (!body) return '';
            return '```' + (matched ? matched[1] : '') + '\n' + body + '\n```\n\n';
          }

          if (tag === 'BLOCKQUOTE') {
            const inner = arenaChildren(node, depth).trim();
            if (!inner) return '';
            return inner.split('\n').map(function(line) {
              return line ? ('> ' + line) : '>';
            }).join('\n') + '\n\n';
          }

          if (tag === 'TABLE') {
            const rows = node.querySelectorAll ? Array.from(node.querySelectorAll('tr')) : [];
            if (!rows.length) return '';
            const header = arenaCells(rows[0]);
            if (!header.length) return '';
            let out = '| ' + header.join(' | ') + ' |\n| ';
            for (let i = 0; i < header.length; i++) out += (i ? ' | ---' : '---');
            out += ' |\n';
            for (let i = 1; i < rows.length; i++) {
              const cells = arenaCells(rows[i]);
              if (!cells.length) continue;
              while (cells.length < header.length) cells.push('');
              out += '| ' + cells.slice(0, header.length).join(' | ') + ' |\n';
            }
            return out + '\n';
          }

          if (tag === 'UL' || tag === 'OL') {
            const ordered = tag === 'OL';
            let counter = 1;
            if (ordered) {
              const start = parseInt(node.getAttribute('start') || '1', 10);
              if (!isNaN(start)) counter = start;
            }
            let out = '';
            const kids = node.children || [];
            for (let i = 0; i < kids.length; i++) {
              const item = kids[i];
              if (item.tagName !== 'LI') continue;
              const head = arenaInline(item).replace(/\n/g, ' ').trim();
              const marker = ordered ? (counter + '. ') : '- ';
              out += arenaIndent(depth) + marker + head + '\n';
              const inner = item.children || [];
              for (let j = 0; j < inner.length; j++) {
                const sub = inner[j].tagName;
                if (sub === 'UL' || sub === 'OL' || sub === 'PRE' || sub === 'TABLE' || sub === 'BLOCKQUOTE') {
                  out += arenaSerialize(inner[j], depth + 1);
                }
              }
              counter += 1;
            }
            return out ? (out + '\n') : '';
          }

          if (tag === 'P') {
            const text = arenaInline(node).trim();
            return text ? text + '\n\n' : '';
          }

          if (arenaHasBlockChild(node)) return arenaChildren(node, depth);
          const text = arenaInline(node).trim();
          return text ? text + '\n\n' : '';
        };

        /** 对外入口。任何异常都回落到 innerText，绝不因为序列化失败把答案变成空。 */
        const arenaToMarkdown = function(node) {
          if (!node) return '';
          try {
            const markdown = arenaSerialize(node, 0)
              .replace(/[ \t]+\n/g, '\n')
              .replace(/\n{3,}/g, '\n\n')
              .trim();
            if (markdown) return markdown;
          } catch (_) {}
          return String(node.innerText || node.textContent || '').trim();
        };
    """
}
