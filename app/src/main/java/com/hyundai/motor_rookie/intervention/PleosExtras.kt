package com.hyundai.motor_rookie.intervention

// ─────────────────────────────────────────────
// 실제 Pleos 창문(Window) 연결 자리  (내비는 PleosNavi.kt 로 연결 완료)
//
// 지금은 전부 주석 처리돼 있어서 빌드에 영향 없음 (시뮬레이션으로 동작).
// Pleos SDK의 정확한 함수 이름을 확인한 뒤 주석을 풀고 맞춰서 쓴다.
//
// 확인 방법 (공조 때와 같은 방식):
//   MainActivity에서  vehicle.getWindow().  까지 치면 자동완성 목록이 뜸
//   → 그 목록(함수 이름, 파라미터) 캡처해서 보내주면 아래 코드를 확정해줄게.
//
// Manifest 권한: pleos.car.permission.CAR_WINDOWS, pleos.car.permission.CONTROL_CAR_WINDOWS
// ─────────────────────────────────────────────

/*
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.coroutines.resume

class PleosWindowController(private val window: Window) : WindowController {

    // Pleos 샘플 앱 기준 창문 위치 범위는 0~10 (0 = 닫힘)
    private val max = 10

    private fun area(seat: WindowSeat) = WindowArea(
        when (seat) {
            WindowSeat.FRONT_LEFT -> WindowValue.ROW_1_LEFT
            WindowSeat.FRONT_RIGHT -> WindowValue.ROW_1_RIGHT
            WindowSeat.REAR_LEFT -> WindowValue.ROW_2_LEFT
            WindowSeat.REAR_RIGHT -> WindowValue.ROW_2_RIGHT
        }
    )

    override suspend fun read(seat: WindowSeat): Int? = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine<Int?> { cont ->
            window.getWindowPosition(
                area = area(seat),
                onComplete = { v -> if (cont.isActive) cont.resume(v * 100 / max) },
                onFailed = { if (cont.isActive) cont.resume(null) }
            )
        }
    }

    override suspend fun write(seat: WindowSeat, percent: Int): String? {
        var error: String? = null
        window.setWindowPosition(
            area = area(seat),
            value = (percent * max / 100f).toInt(),
            onFailed = { e -> error = "창문 설정 실패: ${e.message}" }
        )
        delay(500)
        return error
    }
}

*/
