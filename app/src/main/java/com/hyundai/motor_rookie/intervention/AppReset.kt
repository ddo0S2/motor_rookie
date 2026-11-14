package com.hyundai.motor_rookie.intervention

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File

/**
 * 시연용 "처음 상태로 초기화".
 *  - 앱에 저장된 값(설정, 비상연락처, 파워냅 환경 등 SharedPreferences)을 모두 지운다.
 *  - 허용했던 권한(차량·마이크·위치·전화·알림)을 되돌린다. (안드로이드 13 이상: 앱이 꺼질 때 취소됨)
 *  - 앱을 끈다 → 독에서 곁 아이콘을 다시 누르면 막 설치한 것처럼 처음 설정부터 시작.
 */
object AppReset {
    private const val TAG = "GYEOL_RESET"

    private val PERMISSIONS = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CALL_PHONE,
        "android.permission.POST_NOTIFICATIONS",
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
        "pleos.car.permission.CAR_DRIVE",
    )

    fun resetAndExit(activity: Activity) {
        // 1) 저장된 값 전부 삭제
        val dir = File(activity.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            runCatching { activity.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
            runCatching { activity.deleteSharedPreferences(name) }
        }
        Log.i(TAG, "저장된 값 삭제 완료")

        // 2) 권한 되돌리기 (앱 프로세스가 끝날 때 적용)
        if (Build.VERSION.SDK_INT >= 33) {
            PERMISSIONS.filter { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.forEach { p ->
                runCatching { activity.revokeSelfPermissionsOnKill(listOf(p)) }
                    .onFailure { Log.w(TAG, "권한 되돌리기 실패: $p (${it.message})") }
            }
        }

        // 3) 앱 종료
        activity.finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }
}
