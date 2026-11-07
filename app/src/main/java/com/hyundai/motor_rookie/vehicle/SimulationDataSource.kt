package com.hyundai.motor_rookie.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 시뮬레이션 모드: CSV로 녹화된 주행 데이터를 재생한다.
 *
 * CSV 형식 (첫 줄은 헤더):
 *   t_sec,speed_kmh,steer_deg,gear,wiper,warning
 *   0,0,0,P,0,
 *   1,12.5,0.3,D,0,
 *   ...
 * - gear: P / R / N / D
 * - wiper: 0 또는 1
 * - warning: 비워 두거나 FCW / LDW / DAW
 *
 * @param speedFactor 재생 속도. 60이면 1시간 주행이 1분에 재생된다.
 * @param startHour   주행을 시작한 시각(0~23). 야간 가중치 계산용.
 */
class SimulationDataSource(
    csvLines: List<String>,
    private val scope: CoroutineScope,
    var speedFactor: Double = 60.0,
    private val startHour: Int = 23,
) : VehicleDataSource {

    private val rows: List<Row> = parseCsv(csvLines)
    private val _samples = MutableSharedFlow<VehicleSample>(replay = 1, extraBufferCapacity = 256)
    override val samples: SharedFlow<VehicleSample> = _samples.asSharedFlow()
    private var job: Job? = null

    /** 재생할 전체 길이(초) */
    val durationSec: Double get() = rows.lastOrNull()?.tSec ?: 0.0

    override fun start() {
        if (job != null) return
        job = scope.launch {
            var prevT: Double? = null
            for (r in rows) {
                prevT?.let { delay(((r.tSec - it) * 1000 / speedFactor).toLong().coerceAtLeast(0)) }
                prevT = r.tSec
                _samples.emit(r.toSample(startHour))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    data class Row(
        val tSec: Double,
        val speedKmh: Float,
        val steerDeg: Float,
        val gear: Gear,
        val wiper: Boolean,
        val warning: SafetyWarning?,
    ) {
        fun toSample(startHour: Int): VehicleSample {
            val clockSec = startHour * 3600.0 + tSec
            val hour = ((clockSec / 3600).toInt()) % 24
            return VehicleSample(
                timeMs = (tSec * 1000).toLong(),
                speedKmh = speedKmh,
                steeringDeg = steerDeg,
                gear = gear,
                wiperOn = wiper,
                hourOfDay = hour,
                warning = warning,
            )
        }
    }

    companion object {
        fun parseCsv(lines: List<String>): List<Row> =
            lines.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .drop(1) // 헤더
                .map { line ->
                    val c = line.split(",").map { it.trim() }
                    Row(
                        tSec = c[0].toDouble(),
                        speedKmh = c[1].toFloat(),
                        steerDeg = c[2].toFloat(),
                        gear = when (c.getOrNull(3)?.uppercase()) {
                            "P" -> Gear.PARK
                            "R" -> Gear.REVERSE
                            "N" -> Gear.NEUTRAL
                            "D" -> Gear.DRIVE
                            else -> Gear.UNKNOWN
                        },
                        wiper = c.getOrNull(4) == "1",
                        warning = c.getOrNull(5)?.takeIf { it.isNotEmpty() }
                            ?.let { runCatching { SafetyWarning.valueOf(it.uppercase()) }.getOrNull() },
                    )
                }
                .toList()
    }
}
