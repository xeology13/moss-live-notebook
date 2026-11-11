package net.mosslive.notebook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台服务：Android 14 下麦克风前台服务必须声明 foregroundServiceType="microphone"
 * 且有可见通知，否则息屏几分钟后上行会被冻结。
 */
class SessionService : Service() {

    companion object {
        const val ACTION_START = "net.mosslive.notebook.START"
        const val ACTION_STOP = "net.mosslive.notebook.STOP"
        const val EXTRA_CONTINUE_NOTE_ID = "continue_note_id"
        private const val CH_ID = "call"
        private const val NOTIF_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "通话", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "与 MOSS 的实时语音会话"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_ID)
            .setContentTitle("MOSS 笔记 · 通话中")
            .setContentText("正在听你说")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "挂断",
                    stop
                ).build()
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                MossEngine.onFinished = {
                    MossEngine.onFinished = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                // 继续聊：详情页把目标笔记 id 塞进 intent；没有就是普通通话
                MossEngine.start(this, intent.getStringExtra(EXTRA_CONTINUE_NOTE_ID))
            }
            ACTION_STOP -> {
                MossEngine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MossEngine.onFinished = null
        if (MossEngine.state.value.running) MossEngine.stop()
        super.onDestroy()
    }
}
