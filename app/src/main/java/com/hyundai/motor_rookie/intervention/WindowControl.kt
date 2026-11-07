package com.hyundai.motor_rookie.intervention

import android.util.Log

// ─────────────────────────────────────────────
// 3단계: 창문 환기 + 내비 경유지
//  - 창문: 대각선 2개(운전석 + 뒷좌석 오른쪽)를 조금(30%) 열기 → [닫기]로 원래대로
//  - 내비: 고른 쉼터를 경유지로 추가
// 실제 Pleos 내비: PleosNavi.kt (NaviHelper) / 실제 창문: PleosExtras.kt (API 확인 후)
// ─────────────────────────────────────────────

enum class WindowSeat(val label: String) {
    FRONT_LEFT("운전석"), FRONT_RIGHT("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽")
}

interface WindowController {
    /** 현재 열림 정도 0~100 (모르면 null) */
    suspend fun read(seat: WindowSeat): Int?
    /** 성공하면 null, 실패하면 이유 */
    suspend fun write(seat: WindowSeat, percent: Int): String?
}

class SimWindowController : WindowController {
    val state = mutableMapOf<WindowSeat, Int>().apply { WindowSeat.values().forEach { put(it, 0) } }
    override suspend fun read(seat: WindowSeat): Int = state[seat] ?: 0
    override suspend fun write(seat: WindowSeat, percent: Int): String? {
        state[seat] = percent.coerceIn(0, 100); return null
    }
    fun summary(): String = state.filterValues { it > 0 }.entries
        .joinToString(", ") { "${it.key.label} ${it.value}%" }.ifEmpty { "모두 닫힘" }
}

/** 대각선 2개 환기. 바꾸기 전 상태를 저장해두고 [닫기]로 복원 */
class WindowIntervention(
    private val controller: () -> WindowController,
    private val seats: List<WindowSeat> = listOf(WindowSeat.FRONT_LEFT, WindowSeat.REAR_RIGHT),
    private val openPercent: Int = 30
) {
    private var saved: Map<WindowSeat, Int>? = null
    private var used: WindowController? = null
    val opened: Boolean get() = saved != null

    suspend fun open(): Pair<Boolean, String> {
        if (opened) return true to "이미 창문을 열어 둔 상태예요"
        val ctl = controller()
        val before = seats.associateWith { ctl.read(it) ?: 0 }
        val errors = seats.mapNotNull { ctl.write(it, maxOf(before[it] ?: 0, openPercent)) }
        if (errors.size == seats.size) return false to "창문을 열지 못했어요"
        saved = before; used = ctl
        return true to "창문을 조금 열었어요"
    }

    suspend fun close(): String? {
        val s = saved ?: return null
        val ctl = used ?: controller()
        s.forEach { (seat, p) -> ctl.write(seat, p) }
        saved = null; used = null
        return "창문을 다시 닫았어요"
    }

    val seatText: String get() = seats.joinToString("·") { it.label } + " 창문 환기 중"
}

// ───────────────── 내비 ─────────────────

interface NavigationController {
    /** 연결돼서 바로 쓸 수 있는지 */
    val isReady: Boolean get() = true
    /** 마지막 요청 결과 문구 ("경유지로 추가했어요" / "목적지로 설정했어요") */
    val lastAction: String get() = "경유지로 추가했어요"
    /** 성공하면 null, 실패하면 이유 */
    suspend fun addWaypoint(stop: RestStop): String?
}

/** 시뮬레이션: 실제 내비 대신 기록만 남김 */
class SimNavigationController : NavigationController {
    var lastWaypoint: RestStop? = null
        private set
    override suspend fun addWaypoint(stop: RestStop): String? {
        lastWaypoint = stop
        Log.i("GYEOL_REST", "가짜 내비: 경유지 ${stop.displayName} (${stop.lat}, ${stop.lon})")
        return null
    }
}
