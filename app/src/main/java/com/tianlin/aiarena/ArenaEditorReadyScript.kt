package com.tianlin.aiarena

/** A hydrated composer can be ready while unrelated page resources are still loading. */
internal object ArenaEditorReadyScript {
    fun helper(service: ArenaService): String {
        val mature = when (service) {
            ArenaService.CHATGPT -> "#prompt-textarea.ProseMirror[contenteditable='true']"
            ArenaService.GEMINI -> "rich-textarea .ql-editor[contenteditable='true']"
            else -> ""
        }
        return """
            const arenaEditorReady = input => {
              if (document.readyState === 'complete') return true;
              if (document.readyState !== 'interactive' || !input || !${mature.isNotEmpty()}) return false;
              const selector = ${ArenaJs.quote(mature)};
              const usable = Array.from(document.querySelectorAll(selector)).filter(node => {
                if (!node.isConnected || !node.isContentEditable || node.disabled || node.readOnly ||
                    node.matches(':disabled') || node.closest('[inert]') || node.getAttribute('aria-disabled') === 'true' ||
                    node.getAttribute('aria-readonly') === 'true') return false;
                const rect = node.getBoundingClientRect();
                if (!(rect.width > 0 && rect.height > 0)) return false;
                for (let p = node; p; p = p.parentElement) {
                  const style = getComputedStyle(p);
                  if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) === 0) return false;
                }
                return true;
              });
              return usable.length === 1 && usable[0] === input;
            };
        """.trimIndent()
    }
}
