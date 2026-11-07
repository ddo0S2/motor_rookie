package com.hyundai.motor_rookie.fatigue

import com.hyundai.motor_rookie.vehicle.Gear
import com.hyundai.motor_rookie.vehicle.SimulationDataSource
import com.hyundai.motor_rookie.vehicle.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 피로 감지 로직 테스트. 에뮬레이터 없이 PC에서 바로 돌아간다.
 * Android Studio: 이 파일 왼쪽의 ▶ 버튼 → Run 'FatigueEngineTest'
 */
class FatigueEngineTest {

    private fun runCsv(name: String, startHour: Int = 23): List<FatigueState> {
        val file = listOf("src/main/assets/sim/$name", "app/src/main/assets/sim/$name")
            .map(::File).first { it.exists() }
        val rows = SimulationDataSource.parseCsv(file.readLines())
        val engine = FatigueEngine()
        return rows.map { engine.onSample(it.toSample(startHour)) }
    }

    private fun minuteOf(states: List<FatigueState>, level: FatigueLevel): Int? =
        states.indexOfFirst { it.level.ordinal >= level.ordinal }.takeIf { it >= 0 }?.let { it / 60 }

    @Test
    fun csv_is_parsed() {
        val rows = SimulationDataSource.parseCsv(
            listOf("t_sec,speed_kmh,steer_deg,gear,wiper,warning", "0,0,0,P,0,", "1,90.5,-1.2,D,1,LDW"),
        )
        assertEquals(2, rows.size)
        assertEquals(Gear.DRIVE, rows[1].gear)
        assertEquals(true, rows[1].wiper)
        assertEquals("LDW", rows[1].warning?.name)
    }

    @Test
    fun normal_drive_does_not_trigger_talk() {
        val states = runCsv("normal_drive.csv")
        val max = states.maxOf { it.score }
        println("normal_drive 최고 점수 = $max")
        assertTrue("졸음 없는 주행인데 대화 단계까지 올라감 (최고 $max)", max < 40)
    }

    @Test
    fun drowsy_drive_climbs_through_all_levels() {
        val states = runCsv("drowsy_drive.csv")
        printCurve(states)
        val talk = minuteOf(states, FatigueLevel.TALK)
        val env = minuteOf(states, FatigueLevel.ENVIRONMENT)
        val rest = minuteOf(states, FatigueLevel.REST)
        assertTrue("대화 단계가 너무 이르거나 없음: $talk", talk != null && talk >= 90)
        assertTrue("공조 단계 없음", env != null && env > talk!!)
        assertTrue("쉼터 단계 없음", rest != null && rest > env!!)
        assertTrue("심하게 졸릴 때(130분) 쉼터 단계여야 함", states[130 * 60].level == FatigueLevel.REST)
    }

    @Test
    fun parking_five_minutes_resets_score() {
        val states = runCsv("drowsy_drive.csv")
        val last = states.last()
        assertTrue("P단 휴식 후 점수가 내려가야 함 (${last.score})", last.score <= 10)
        assertEquals(FatigueLevel.NORMAL, last.level)
    }

    @Test
    fun three_no_responses_are_counted() {
        val engine = FatigueEngine()
        engine.onSample(VehicleSample(0, 100f, 0f, Gear.DRIVE))
        repeat(3) { engine.onTalkResult(answered = false, correct = false, responseMs = 0) }
        assertEquals(3, engine.state.value.noResponseCount)
        engine.onTalkResult(answered = true, correct = true, responseMs = 1500)
        assertEquals(0, engine.state.value.noResponseCount)
    }

    @Test
    fun night_raises_score() {
        val day = runCsv("drowsy_drive.csv", startHour = 10)
        val night = runCsv("drowsy_drive.csv", startHour = 23)
        val i = 110 * 60
        assertTrue(night[i].score > day[i].score)
    }

    private fun printCurve(states: List<FatigueState>) {
        println("분  점수  단계        time  steer speed signal  m")
        for (min in 0 until states.size / 60 step 5) {
            val s = states[min * 60]
            println(
                "%3d  %3d   %-10s %.2f  %.2f  %.2f  %.2f   %.2f %s".format(
                    min, s.score, s.level.name, s.signals.time, s.signals.steer, s.signals.speed,
                    s.signals.signal, s.multiplier, s.reasons.joinToString(","),
                ),
            )
        }
    }
}
