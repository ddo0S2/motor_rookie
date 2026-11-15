package com.hyundai.motor_rookie.intervention

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────
// 실제 Pleos 내비 연결 (NaviHelper SDK 3.0.5, 신청 없이 사용 가능)
//  - 안내 중이면  addWaypoint  → 쉼터를 첫 번째 경유지로 추가
//  - 안내 중이 아니면 requestRoute → 쉼터를 목적지로 설정
//  - 설정한 경로는 왼쪽 Driving view(TBT)와 내비 앱에 그대로 표시된다
//  - 곁이 보낸 길안내가 끝나면(안내 종료 · 도착 · 취소) 곁 화면을 다시 앞으로 불러온다
//    안드로이드가 뒤에 있는 앱이 스스로 앞으로 나오는 걸 막을 수 있어서,
//    "곁으로 돌아가기" 알림도 같이 띄운다 (알림을 누르면 확실히 돌아옴)
//
// 필요한 것
//  - app/build.gradle.kts : implementation("ai.pleos.playground:NaviHelper:3.0.5")
//  - Manifest : pleos.car.permission.NAVI_ROUTE / NAVI_ROUTE_SEARCH / NAVI_CUSTOM_MAP
// ─────────────────────────────────────────────

class PleosNavigationController(context: Context) : NavigationController {

    private val appContext = context.applicationContext
    private val helper = NaviHelper(appContext)

    /** 길안내가 끝나면 곁으로 돌아올지. MainActivity 가 설정값을 연결한다 */
    var autoReturn: () -> Boolean = { true }
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 곁이 보낸 길안내가 진행 중인지 (사용자가 내비를 따로 쓴 경우와 구분) */
    @Volatile private var waitingReturn = false
    /** 곁이 보낸 길안내가 실제로 시작됐는지. 시작 전에 오는 취소·종료 신호(경로 미리보기 등)는 무시 */
    @Volatile private var guideStarted = false
    /** 경로를 보낸 시각. 직후 몇 초 동안 오는 종료 신호(이전 경로 정리, 재탐색)는 무시 */
    @Volatile private var requestedAt = 0L
    /** 길안내가 시작된 시각. [안내 시작] 직후 같이 오는 "경로 탐색 끝" 신호는 종료가 아니라서 무시 */
    @Volatile private var startedAt = 0L

    @Volatile var connected = false
        private set
    @Volatile private var guiding = false
    @Volatile private var lastError: String? = null
    override var lastAction: String = "경유지로 추가했어요"
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            guiding = status == NavigationStatus.ROUTE_DRIVE
            if (guiding && waitingReturn) markStarted()
            Log.d(TAG, "내비 상태 신호: $status (돌아오기 대기=$waitingReturn, 시작됨=$guideStarted)")
            // 곁이 보낸 안내가 시작된 뒤 "안내 아님" 상태면 안내 종료로 본다
            if (!guiding) onGuidanceFinished("상태 변경($status)")
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            guiding = routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING ||
                    routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING
            if (guiding && waitingReturn) markStarted()
            Log.d(TAG, "경로 상태: ${routeStateInfo.naviMode} (돌아오기 대기=$waitingReturn, 시작됨=$guideStarted)")
            // 2초마다 물어본 대답으로도 확인 (신호를 놓쳐도 다음 확인 때 돌아옴)
            if (!guiding) onGuidanceFinished("경로 상태 변경(${routeStateInfo.naviMode})")
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            guiding = true
            if (waitingReturn) markStarted()
            Log.d(TAG, "경로 안내 시작: $routeStartInfo")
        }
        override fun onRouteCancelled() {
            guiding = false
            Log.d(TAG, "경로 안내 취소")
            onGuidanceFinished("취소")
        }
        override fun onRouteEnded() {
            // 주의: Pleos 내비는 [안내 시작]을 누른 순간에도 이 신호를 보낸다 (경로 탐색이 끝났다는 뜻).
            // 그래서 안내 종료 판단에는 쓰지 않고, 상태 변경(ROUTE_DRIVE → 아님)으로 판단한다.
            Log.d(TAG, "경로 탐색 끝 신호 (무시)")
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            guiding = false
            Log.d(TAG, "목적지 도착: $destinationArrivedInfo")
            onGuidanceFinished("도착")
        }
        override fun onError(errorCode: NaviErrorCode) {
            lastError = when (errorCode) {
                NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "현재 위치에서 너무 멀어요"
                NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "너무 가까워요"
                else -> "경로를 찾지 못했어요 (${errorCode.name})"
            }
            Log.w(TAG, "내비 오류: $errorCode")
        }
    }

    @Volatile private var connecting = false

    /**
     * 앱 시작 때 바로 연결하면 Pleos 내비 앱이 앞으로 나와 곁 화면을 덮어서,
     * [안내 시작]을 처음 누를 때 연결한다.
     */
    private fun connect() {
        if (connected || connecting) return
        connecting = true
        runCatching {
            helper.initialize(
                onConnected = {
                    helper.addListener(listener)
                    connected = true; connecting = false
                    runCatching { helper.getRouteStateInfo() }
                    Log.i(TAG, "NaviHelper 연결됨")
                },
                onFailed = { t -> connected = false; connecting = false; Log.w(TAG, "NaviHelper 연결 실패", t) }
            )
        }.onFailure { connecting = false; Log.w(TAG, "NaviHelper initialize 예외", it) }
    }

    /** 실제 내비를 쓸 수 있는 앱인지 (연결은 처음 요청할 때) */
    override val isReady: Boolean get() = true

    /** 연결이 안 돼 있으면 연결하고 최대 4초 기다린다 */
    private suspend fun ensureConnected(): Boolean {
        if (connected) return true
        connect()
        var waited = 0
        while (!connected && waited < 4_000) { delay(200); waited += 200 }
        if (!connected) return false
        delay(500)   // 상태 콜백(안내 중인지) 받을 시간
        return true
    }

    /** 내비 화면에서 장소 검색 (연료 부족 → "주유소"). 성공하면 null, 실패하면 이유 */
    suspend fun searchNearby(keyword: String): String? {
        if (!ensureConnected()) return NOT_CONNECTED
        return try {
            armReturn()                       // 검색 결과로 안내가 끝나면 곁으로
            helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
            Log.i(TAG, "내비 검색: $keyword")
            null
        } catch (e: SecurityException) {
            "내비 권한이 없어요"
        } catch (t: Throwable) {
            Log.w(TAG, "내비 검색 실패", t)
            t.message ?: "내비 검색 실패"
        }
    }

    override suspend fun addWaypoint(stop: RestStop): String? {
        if (!ensureConnected()) return NOT_CONNECTED
        lastError = null
        return try {
            armReturn()                     // 이 안내가 "시작된 뒤 끝나면" 곁으로 돌아온다
            if (guiding) {
                helper.addWaypoint(
                    RequestWaypointInfo(
                        latitude = stop.lat,
                        longitude = stop.lon,
                        poiName = stop.displayName,
                        waypointIndex = WaypointIndex.FIRST,
                        poiId = "",
                        address = stop.route,
                        poiSubId = "0"
                    )
                )
                lastAction = "경유지로 추가했어요"
            } else {
                helper.requestRoute(
                    RouteInfo(
                        latitude = stop.lat,
                        longitude = stop.lon,
                        displayLatitude = null,
                        displayLongitude = null,
                        poiName = stop.displayName,
                        poiId = "",
                        poiSubId = "0",
                        address = stop.route,
                        routeOption = RouteOption.RECOMMENDED
                    )
                )
                lastAction = "목적지로 설정했어요"
            }
            Log.i(TAG, "${stop.displayName} $lastAction (${stop.lat}, ${stop.lon})")
            delay(1_500)                  // 오류 콜백을 잠깐 기다림
            if (lastError != null) stopWatching()
            lastError
        } catch (e: SecurityException) {
            "내비 권한이 없어요 (Manifest NAVI_ROUTE_SEARCH 확인)"
        } catch (t: Throwable) {
            Log.w(TAG, "내비 요청 실패", t)
            t.message ?: "내비 요청 실패"
        }
    }

    /** 곁이 보낸 길안내가 끝났을 때 (종료 · 도착 · 취소 콜백이 겹쳐 와도 한 번만) */
    /** 곁이 경로를 보냈을 때: 이 길안내가 "시작된 뒤 끝나면" 곁으로 돌아오도록 준비 */
    private fun armReturn() {
        waitingReturn = autoReturn()
        guideStarted = guiding          // 이미 안내 중(경유지 추가)이면 시작된 걸로 본다
        requestedAt = android.os.SystemClock.elapsedRealtime()
        startedAt = requestedAt
        mainHandler.removeCallbacks(pollTask)
        if (waitingReturn) {
            mainHandler.postDelayed(pollTask, POLL_MS)
            GuideWatchService.start(appContext)     // 내비가 앞에 있는 동안 곁이 멈추지 않게
        }
    }

    private fun stopWatching() {
        waitingReturn = false
        mainHandler.removeCallbacks(pollTask)
        GuideWatchService.stop(appContext)
    }

    /**
     * 에뮬레이터 내비는 [안내 시작]/[안내 종료] 때 신호를 먼저 보내 주지 않아서,
     * 곁이 보낸 길안내를 기다리는 동안 2초마다 "지금 경로 상태"를 물어본다.
     * 대답은 onRouteStateInfo 로 오고, 안내 중 → 안내 아님 으로 바뀌면 곁으로 돌아온다.
     */
    private val pollTask: Runnable = object : Runnable {
        override fun run() {
            if (!waitingReturn || !connected) return
            val waited = android.os.SystemClock.elapsedRealtime() - requestedAt
            if (!guideStarted && waited > 10 * 60_000L) {          // 10분 동안 안내를 시작 안 했으면 그만
                Log.d(TAG, "안내가 시작되지 않아 돌아오기 대기 종료")
                stopWatching()
                return
            }
            val r = runCatching { helper.getRouteStateInfo() }
            if (guideStarted) Log.d(TAG, "상태 확인 요청 (${if (r.isSuccess) "보냄" else "실패: ${r.exceptionOrNull()?.message}"})")
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    private fun markStarted() {
        if (!guideStarted) {
            guideStarted = true
            startedAt = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "곁이 보낸 길안내 시작됨 → 안내가 끝나면 곁으로 돌아와요")
        }
    }

    private fun onGuidanceFinished(why: String) {
        if (!waitingReturn) return
        val now = android.os.SystemClock.elapsedRealtime()
        val sinceRequest = now - requestedAt
        val sinceStart = now - startedAt
        // 시작 직후 1.5초는 이전 상태 대답이 늦게 올 수 있어서 무시 (그 뒤 2초마다 다시 확인)
        if (!guideStarted) return                       // 아직 [안내 시작] 전
        if (sinceRequest < 5_000 || sinceStart < 1_500) {
            Log.d(TAG, "안내 $why 신호 무시 (시작 후 ${sinceStart}ms)")
            return
        }
        Log.i(TAG, "곁이 보낸 길안내 $why → 곁으로 돌아오기")
        stopWatching()
        guideStarted = false
        mainHandler.post {
            bringAppToFront()
            showReturnNotification()
        }
    }

    /** 곁 화면을 다시 앞으로. 앱 아이콘을 누른 것과 같아서 보던 화면 그대로 돌아온다 */
    private fun bringAppToFront() {
        val moved = runCatching {
            val am = appContext.getSystemService(ActivityManager::class.java)
            val task = am?.appTasks?.firstOrNull() ?: return@runCatching false
            task.moveToFront()
            true
        }.getOrDefault(false)
        runCatching {
            launchIntent()?.let { appContext.startActivity(it) }
        }.onFailure { Log.w(TAG, "곁으로 돌아오기 실패", it) }
        Log.i(TAG, "길안내 끝 → 곁으로 돌아오기 (moveToFront=$moved)")
    }

    /** 자동으로 못 돌아온 경우를 위한 알림. 누르면 곁으로 돌아온다 */
    private fun showReturnNotification() {
        runCatching {
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "길안내 종료", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                appContext, 0, launchIntent() ?: return,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("길안내가 끝났어요")
                .setContentText("눌러서 곁으로 돌아가기")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTI_ID, n)
        }.onFailure { Log.w(TAG, "알림 실패 (알림 권한 확인)", it) }
    }

    /** 런처에서 앱 아이콘을 누른 것과 같은 Intent (새로 만들지 않고 기존 화면을 앞으로) */
    private fun launchIntent(): Intent? =
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

    fun release() {
        stopWatching()
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
        connected = false
    }

    companion object {
        private const val TAG = "GYEOL_NAVI"
        const val NOT_CONNECTED = "내비게이션에 연결되지 않았어요"
        private const val CHANNEL_ID = "gyeol_navi"
        private const val NOTI_ID = 3101
        private const val POLL_MS = 2_000L
    }
}