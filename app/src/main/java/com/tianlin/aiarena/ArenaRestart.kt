package com.tianlin.aiarena

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.webkit.CookieManager

/**
 * 「重启应用」。
 *
 * 家人手机上出问题时，最有效也最容易执行的一句指导就是"重启一下"。但 Android 没有
 * 官方的"重启自己"接口：`AlarmManager` 定时拉起在 12+ 上会被电池策略推迟到几分钟后，
 * 用户会以为 App 直接闪退了。这里用 ProcessPhoenix 的做法：在**另一个进程**里起一个
 * 透明 Activity，由它杀掉主进程再拉起启动页，然后自我退出。整个过程约半秒，
 * 界面表现是"闪一下回到首页"。
 *
 * 重启前要把该落盘的都落盘：Cookie 强制 flush（登录态），会话由调用方先同步保存。
 */
object ArenaRestart {
    const val EXTRA_MAIN_PID = "com.tianlin.aiarena.RESTART_MAIN_PID"

    fun trigger(context: Context) {
        runCatching { CookieManager.getInstance().flush() }
        val intent = Intent(context, ArenaRestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_MAIN_PID, Process.myPid())
        context.startActivity(intent)
        if (context is Activity) context.finish()
        Runtime.getRuntime().exit(0)
    }
}

/** 跑在独立进程 `:arena_restart` 里，见 [ArenaRestart]。 */
class ArenaRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(ArenaRestart.EXTRA_MAIN_PID, -1)
        if (mainPid > 0 && mainPid != Process.myPid()) {
            Process.killProcess(mainPid)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) startActivity(launch)
        finish()
        Process.killProcess(Process.myPid())
    }
}
