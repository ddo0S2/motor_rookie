package com.hyundai.motor_rookie.intervention

import com.hyundai.motor_rookie.fatigue.FatigueState

/**
 * 1번 파트(감지 모듈)와 2·3번 파트(개입)를 잇는 값 묶음. MainActivity 가 만들어 넘긴다.
 *
 * @param live          감지 모듈의 지금 상태 (점수·운전 시간·신호). 아직 없으면 null
 * @param useDetection  true = 졸음 지수를 감지 모듈에서 받음, false = 시뮬레이션 패널 슬라이더로 직접 조절
 * @param onUseDetectionChange 시뮬레이션 패널의 스위치를 바꿨을 때
 * @param feedScore     점수를 개입 매니저에 넣는 함수. 단계가 오르면 "지금 ○○이 필요합니다" 안내 후 개입 시작
 * @param onRestart     감지 모듈을 처음부터 다시 시작 (CSV 재생도 처음부터)
 * @param onOpenPowerNap 파워냅 환경 저장 화면 열기 (5번 파트)
 * @param scenarioLevel 테스트 모드의 운전 상태 0 정상 ~ 3 아주 졸림 (테스트 모드가 아니면 null)
 * @param onScenarioLevel 시뮬레이션 패널에서 운전 상태 버튼을 눌렀을 때
 */
data class DetectionLink(
    val live: FatigueState?,
    val useDetection: Boolean,
    val onUseDetectionChange: (Boolean) -> Unit,
    val feedScore: (Int) -> Unit,
    val onRestart: () -> Unit,
    val onOpenPowerNap: (() -> Unit)? = null,
    val scenarioLevel: Int? = null,
    val onScenarioLevel: ((Int) -> Unit)? = null,
)
