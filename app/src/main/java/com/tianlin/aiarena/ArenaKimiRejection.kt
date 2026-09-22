package com.tianlin.aiarena

/** Explicit, visible website refusal. A receipt for this request takes precedence. */
internal object ArenaKimiRejection {
    const val busyDetail = "Kimi 官网当前繁忙，尚未确认送达；请稍后到原网页核对，不会自动重复发送"
    const val membershipDetail = "Kimi 网页提示当前模型或功能需要会员，问题没有发出；请打开原网页换用可用模型后重试"

    val expression = """
        (function() {
          const dialogs = Array.from(document.querySelectorAll('.modal-mask, [data-testid="confirm-dialog"]'));
          for (const dialog of dialogs) {
            const rect = dialog.getBoundingClientRect();
            const style = getComputedStyle(dialog);
            if (rect.width <= 2 || rect.height <= 2 || style.display === 'none' || style.visibility === 'hidden') continue;
            const words = String(dialog.innerText || '').replace(/\s+/g, ' ');
            if (/Too many people are chatting with Kimi|当前.{0,8}(使用|访问|聊天).{0,8}(人数过多|人太多)|服务繁忙|系统繁忙/i.test(words)) return 'busy';
            if (/Upgrade your membership|higher-tier members|members only|升级会员|开通会员|会员专享|仅.{0,8}会员/i.test(words)) return 'membership';
          }
          return '';
        })()
    """.trimIndent()
}
