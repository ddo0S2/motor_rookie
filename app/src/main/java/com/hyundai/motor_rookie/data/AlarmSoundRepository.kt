package com.hyundai.motor_rookie.data

import android.content.Context
import android.media.RingtoneManager
import android.util.Log

/** 기기에 들어 있는 시스템 알람음 하나 */
data class AlarmSound(
    val title: String,
    val uri: String,
)

/**
 * 시스템 알람음 목록을 읽어 옵니다.
 * 파일 위치를 직접 찾지 않고 RingtoneManager 를 통해 가져오므로, 차량마다 저장 위치가 달라도 똑같이 동작합니다.
 * 목록을 읽지 못하거나 알람음이 없는 차량이면 빈 목록을 돌려줍니다(그 경우 기본 알람음으로 울립니다).
 */
class AlarmSoundRepository(context: Context) {

    private val appContext = context.applicationContext

    fun load(): List<AlarmSound> = try {
        val manager = RingtoneManager(appContext).apply { setType(RingtoneManager.TYPE_ALARM) }
        manager.cursor.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
                    val uri = manager.getRingtoneUri(cursor.position) ?: continue
                    add(AlarmSound(title.replace('_', ' '), uri.toString()))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "알람음 목록을 읽지 못했어요", e)
        emptyList()
    }

    private companion object {
        const val TAG = "AlarmSounds"
    }
}