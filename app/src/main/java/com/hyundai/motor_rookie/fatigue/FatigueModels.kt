package com.hyundai.motor_rookie.fatigue

/** 개입 단계. 2·3번 담당은 이 값만 보고 대화/공조/쉼터 안내를 시작하면 된다. */
enum class FatigueLevel(val label: String) {
    NORMAL("좋음"),       // 0단계: 관찰
    TALK("주의"),         // 1단계: 대화 (F ≥ 40)
    ENVIRONMENT("피곤함"), // 2단계: 공조 (F ≥ 60)
    REST("휴식 필요"),     // 3단계: 창문 + 쉼터 (F ≥ 75)
}

/** 설정 화면의 "개입 민감도". 숫자는 [대화, 공조, 쉼터] 시작 점수. */
enum class Sensitivity(val talk: Int, val environment: Int, val rest: Int) {
    LOW(45, 65, 80),
    NORMAL(40, 60, 75),
    HIGH(35, 55, 70),
}

/** 각 신호 값 (모두 0~1) */
data class FatigueSignals(
    val time: Double = 0.0,   // s_time   연속 주행 시간
    val steer: Double = 0.0,  // s_steer  핸들 미세 보정 패턴
    val speed: Double = 0.0,  // s_speed  속도 변동
    val talk: Double = 0.0,   // s_talk   대화 반응 (2번 담당이 결과를 넣어 줌)
    val signal: Double = 0.0, // s_signal 순정 경고 (10분 안에 2번 이상이면 1.0)
)

/** 화면과 다른 팀원이 구독하는 최종 결과 */
data class FatigueState(
    val score: Int = 0,
    val level: FatigueLevel = FatigueLevel.NORMAL,
    val signals: FatigueSignals = FatigueSignals(),
    val multiplier: Double = 1.0,
    val reasons: List<String> = emptyList(), // 예: ["야간", "고속 직진"]
    val drivingMinutes: Int = 0,
    val parked: Boolean = false,
    val baselineReady: Boolean = false,
    val noResponseCount: Int = 0,             // 연속 무응답 횟수 (3이면 비상 모드 후보)
)

/** 튜닝용 숫자 모음. 시뮬레이션 결과를 보면서 여기만 고치면 된다. */
data class FatigueConfig(
    // 점수 가중치 (기획서 5.2)
    val wTime: Double = 0.25,
    val wSteer: Double = 0.30,
    val wSpeed: Double = 0.15,
    val wTalk: Double = 0.30,
    val wSignalBonus: Double = 0.15, // s_signal 은 "보너스"로 더함

    // 상황 가중치 m
    val nightFactor: Double = 1.2,     // 22시~06시
    val wiperFactor: Double = 1.1,     // 비·눈
    val congestionFactor: Double = 1.1, // 정체
    val straightFactor: Double = 1.1,  // 고속 직진

    // 신호 계산
    val windowSec: Int = 60,             // 핸들·속도를 보는 구간
    val fullFatigueSec: Double = 7200.0, // 2시간 연속 주행 = s_time 1.0
    val restMinSec: Int = 300,           // P단 5분 이상이면 휴식으로 인정
    val highwaySpeedKmh: Float = 60f,    // 이 속도 이상일 때만 핸들·속도 분석
    val baselineSec: Int = 900,          // 처음 15분을 '평소 상태'로 학습
    val defaultMicroRate: Double = 10.0, // 기준선 학습 전 기본값 (분당 미세 보정 횟수)
    val defaultSpeedStd: Double = 2.0,   // 기준선 학습 전 기본값 (km/h)
    val microDropStart: Double = 0.25,   // 미세 보정이 평소보다 25% 넘게 줄면 점수 시작 (그 아래는 자연스러운 흔들림)
    val microDropFull: Double = 0.7,     // 70% 줄면 1.0
    val jerksFull: Double = 2.0,         // 1분에 급조작 2번이면 1.0
    val speedStdRatioFull: Double = 3.0, // 속도 출렁임이 평소의 3배면 1.0
    val steadyTrendKmhPerSec: Double = 0.3, // 이보다 빠르게 가속·감속 중이면 분석하지 않음
    val leaveDecaySec: Double = 120.0,   // 고속 정속 주행이 아닐 때 핸들·속도 점수가 줄어드는 속도
    val congestionWindowSec: Int = 300,
    val congestionSpeedKmh: Double = 30.0,
    val signalWindowSec: Int = 600,      // 순정 경고를 세는 구간 (10분)
    val talkDecaySec: Double = 600.0,    // 대화 점수가 자연히 줄어드는 속도
    val smoothingSec: Double = 20.0,     // 점수가 튀지 않게 부드럽게
    val hysteresis: Int = 5,             // 단계가 내려갈 때는 기준보다 5점 더 낮아야
)
