# 구조와 역할 간 약속

세 명이 동시에 작업해도 충돌하지 않도록, **폴더(=담당)를 나누고 서로 주고받는 것만 아래처럼 고정**합니다.
이 약속을 바꾸고 싶으면 PR 전에 팀 채팅에서 먼저 이야기합니다.

## 담당 폴더

| 폴더 (`com.hyundai.motor_rookie.`) | 담당 | 내용 |
|---|---|---|
| `vehicle/` | 1번 감지 | `VehicleSample`, `VehicleDataSource`, 실제 SDK 소스, 시뮬레이션 소스 |
| `fatigue/` | 1번 감지 | `FatigueEngine`, `FatigueState`, `DetectionModule`, 디버그 패널 |
| `talk/` | 2번 대화·환경 | 퀴즈 15문항, 출제·채점, STT/TTS |
| `climate/` | 2번 대화·환경 | 온도·송풍 개입과 되돌리기 |
| `rest/` | 3번 휴식·파워냅 | 창문 열기, 졸음쉼터 검색·경유지 추가 (NaviHelper) |
| `powernap/` | 3번 휴식·파워냅 | 파워냅 설정 저장, 시작·종료, 원위치 |
| `ui/main/` | 1번 | 주행 메인 화면 (피로 게이지) |
| `ui/talk/` | 2번 | 대화 화면, 개입 알림 배너 |
| `ui/rest/`, `ui/nap/` | 3번 | 쉼터 안내, 파워냅 화면 |
| `ui/settings/`, `ui/theme/` | 공용 | 고칠 때는 PR에 꼭 적기 |
| `MainActivity.kt` | 공용 | 연결 코드만. 기능 코드는 넣지 않기 |

## 1번(감지)이 제공하는 것

```kotlin
// 앱 시작 시 한 번 만들어서 모두가 같이 씀 (MainActivity 또는 Application)
val detection = DetectionModule(
    VehicleSources.create(context, scope, simulation = 설정값),
    scope,
)
detection.start()
```

| 이름 | 타입 | 쓰는 사람 | 용도 |
|---|---|---|---|
| `detection.fatigue` | `StateFlow<FatigueState>` | 2번, 3번, 화면 | 점수·단계 구독 |
| `FatigueState.level` | `NORMAL / TALK / ENVIRONMENT / REST` | 2번, 3번 | 개입 시작 신호 |
| `FatigueState.noResponseCount` | `Int` | (비상 모드 확장 시) | 연속 무응답 횟수 |
| `detection.vehicle` | `SharedFlow<VehicleSample>` | 3번 | 기어 P 확인, 속도 0 확인 |
| `detection.engine.sensitivity` | `Sensitivity` | 설정 화면 | 개입 민감도 |

### 단계가 바뀌는 규칙
- 올라갈 때: 점수가 기준(40/60/75)을 넘는 순간 바로
- 내려갈 때: 기준보다 **5점 더** 내려가야 (왔다 갔다 방지)
- P단 5분 이상이면 휴식으로 보고 점수 0부터 다시

## 2번(대화·환경)이 1번에게 알려 주는 것

```kotlin
// 퀴즈 한 문제가 끝날 때마다
detection.engine.onTalkResult(
    answered = true,        // 무응답이면 false
    correct = false,        // 틀렸으면 false
    responseMs = 4200,      // 질문이 끝나고 대답까지 걸린 시간
)
```
→ 무응답 +0.4, 오답 +0.2, 3초 넘게 느림 +0.15, 빠른 정답 −0.15 만큼 `s_talk` 가 바뀝니다.

## 3번(휴식·파워냅)이 1번에게 알려 주는 것

```kotlin
// 파워냅이 끝나 기상했을 때
detection.engine.onRestTaken()
```
→ 연속 주행 시간과 점수를 0으로 되돌립니다. (P단 5분 이상이면 자동으로도 됩니다.)

## 개입을 시작하는 예시 (2·3번용)

```kotlin
scope.launch {
    detection.fatigue
        .map { it.level }
        .distinctUntilChanged()          // 단계가 "바뀔 때만"
        .collect { level ->
            when (level) {
                FatigueLevel.TALK -> talk.startQuiz()
                FatigueLevel.ENVIRONMENT -> climate.apply()
                FatigueLevel.REST -> rest.openWindowAndSuggestShelter()
                FatigueLevel.NORMAL -> climate.restore()
            }
        }
}
```

## 시뮬레이션 모드에서 주의할 점
- 시간은 `VehicleSample.timeMs`(주행 시작 후 경과 시간)를 기준으로 합니다. 60배속 재생 중에는 벽시계(System.currentTimeMillis)를 쓰면 계산이 어긋납니다.
- 차량 제어(Set)는 시뮬레이션 중에도 실제 에뮬레이터에 보냅니다. 그래서 시연 영상에서 공조 바가 바뀌는 게 보입니다.
