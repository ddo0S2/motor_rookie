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
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────
// 실제 Pleos 내비 연결 (NaviHelper SDK 3.0.5, 신청 없이 사용 가능)
//  - 안내 중이면  addWaypoint  → 쉼터를 첫 번째 경유지로 추가
//  - 안내 중이 아니면 requestRoute → 쉼터를 목적지로 설정
//  - 설정한 경로는 왼쪽 Driving view(TBT)와 내비 앱에 그대로 표시된다
//
// 필요한 것
//  - app/build.gradle.kts : implementation("ai.pleos.playground:NaviHelper:3.0.5")
//  - Manifest : pleos.car.permission.NAVI_ROUTE / NAVI_ROUTE_SEARCH / NAVI_CUSTOM_MAP
// ─────────────────────────────────────────────

class PleosNavigationController(context: Context) : NavigationController {

    private val helper = NaviHelper(context.applicationContext)

    @Volatile var connected = false
        private set
    @Volatile private var guiding = false
    @Volatile private var lastError: String? = null
    override var lastAction: String = "경유지로 추가했어요"
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            guiding = status == NavigationStatus.ROUTE_DRIVE
            Log.d(TAG, "내비 상태: $status")
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            guiding = routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING ||
                routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            guiding = true
            Log.d(TAG, "경로 안내 시작: $routeStartInfo")
        }
        override fun onRouteCancelled() { guiding = false }
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

    override suspend fun addWaypoint(stop: RestStop): String? {
        if (!connected) {
            connect()
            var waited = 0
            while (!connected && waited < 4_000) { delay(200); waited += 200 }
            if (!connected) return NOT_CONNECTED
            delay(500)   // 상태 콜백(안내 중인지) 받을 시간
        }
        lastError = null
        return try {
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
            lastError
        } catch (e: SecurityException) {
            "내비 권한이 없어요 (Manifest NAVI_ROUTE_SEARCH 확인)"
        } catch (t: Throwable) {
            Log.w(TAG, "내비 요청 실패", t)
            t.message ?: "내비 요청 실패"
        }
    }

    fun release() {
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
        connected = false
    }

    companion object {
        private const val TAG = "GYEOL_NAVI"
        const val NOT_CONNECTED = "내비게이션에 연결되지 않았어요"
    }
}
