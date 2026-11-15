package com.hyundai.motor_rookie.intervention

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 곁이 보낸 길안내를 지켜보는 동안 앱이 멈추지 않게 붙잡아 두는 서비스.
 *
 * 내비가 앞에 있으면 곁은 뒤로 가고, 안드로이드 14는 뒤에 있는 앱을 몇 초 뒤 "얼려서(freeze)"
 * 내비 신호도 못 받고 2초마다 하는 상태 확인도 멈춘다. → 안내 종료를 못 알아챔.
 * 포그라운드 서비스가 돌고 있으면 얼리지 않는다. (알림 영역에 "길안내를 지켜보고 있어요"가 보임)
 *
 * PleosNavigationController 가 경로를 보낼 때 start, 곁으로 돌아오거나 그만 기다릴 때 stop.
 */
class GuideWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "길안내 지켜보기", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("곁이 길안내를 지켜보고 있어요")
            .setContentText("안내가 끝나면 곁으로 돌아와요")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTI_ID, n, type)
        } catch (e: Exception) {
            Log.w(TAG, "포그라운드 서비스 시작 실패", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "GYEOL_NAVI"
        private const val CHANNEL_ID = "gyeol_watch"
        private const val NOTI_ID = 3102

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, GuideWatchService::class.java)) }
                .onFailure { Log.w(TAG, "길안내 지켜보기 서비스 시작 실패: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GuideWatchService::class.java)) }
        }
    }
}
