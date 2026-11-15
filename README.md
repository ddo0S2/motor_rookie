# 곁

카메라 없이 차량 데이터만으로 졸음을 알아채고, 말을 걸어 깨우고, 쉼터로 안내해 파워냅과 재출발 확인까지 챙기는 Pleos Connect 앱

## 실행 방법

### APK 설치

1. [GitHub Release v1.0](https://github.com/ddo0S2/motor_rookie/releases/tag/v1.0)에서 `motor_rookie_gyeot_release.apk`를 내려받습니다. (저장소의 [`apk/모터루키_곁_release.apk`](https://github.com/ddo0S2/motor_rookie/raw/master/apk/%EB%AA%A8%ED%84%B0%EB%A3%A8%ED%82%A4_%EA%B3%81_release.apk)도 같은 파일입니다.)
2. Pleos Connect 에뮬레이터를 실행하고, APK를 에뮬레이터 화면에 끌어다 놓거나 `adb install motor_rookie_gyeot_release.apk`로 설치합니다.
3. debug 버전이 설치돼 있으면 먼저 삭제합니다: `adb uninstall com.hyundai.motor_rookie`

### 소스 빌드

1. Android Studio에서 저장소를 열고 ▶ Run 'app'.
2. Pleos Maven 저장소(`nexus-playground.pleos.ai`)는 `settings.gradle.kts`에 포함돼 있습니다.

### 모드 바꾸기

`app/src/main/java/com/hyundai/motor_rookie/MainActivity.kt` 상단의 스위치를 바꾼 뒤 다시 빌드(▶ Run)합니다.

| 스위치 | 위치 | 값 | 설명 |
|---|---|---|---|
| `dataMode` | `MainActivity.kt` | `SCENARIO` / `CSV` / `LIVE` | 핸들·속도 값을 어디서 받을지 (기본 `SCENARIO`) |
| `liveDemo` | `MainActivity.kt` | `true` / `false` | LIVE 시연용 시간 단축 (기본 `false`) |
| `NAP_FAST_TEST` | `ui/PowerNapViewModel.kt` | `true` / `false` | 파워냅 시간 단축, 1분 → 4초 (기본 `false`) |

| 모드 | 핸들·속도 값 | 평소 운전 학습 | 쓰임 |
|---|---|---|---|
| `SCENARIO` (제출 APK) | 시뮬레이션 패널에서 고른 졸음 상태(정상 · 조금 · 많이 · 아주 졸림)대로 앱이 생성 | 약 1분 (5배속) | 단계별 기능을 빠르게 확인 |
| `CSV` | `assets/sim/drowsy_drive.csv`(약 2시간 34분 주행)를 60배속 재생 | 자동 | 녹화 데이터로 전체 흐름 확인 |
| `LIVE` | Virtual Console(또는 실제 차량)의 속도 · 핸들 · 기어를 실시간 수신 | 15분 (`liveDemo = true`면 1분) | 실제 주행과 같은 조건 |

- 세 모드 모두 졸음 지수는 **같은 감지 엔진**이 계산합니다.
- 기어는 모든 모드에서 Virtual Console 값을 따르며, **D단에서만 감지가 진행**됩니다.

### LIVE 모드: Virtual Console로 실시간 조작

| 순서 | 할 일 | 명령어 / 방법 |
|---|---|---|
| 1 | LIVE로 빌드 | `dataMode = DataMode.LIVE` 후 ▶ Run |
| 2 | Virtual Console 연결 | `adb forward tcp:8088 tcp:8088` |
| 3 | Virtual Console 열기 | 브라우저에서 http://127.0.0.1:8088/ |
| 4 | 출발 | 기어 **D**, 속도 60km/h 이상 유지 (`liveDemo = true`면 40km/h 이상) |
| 5 | 평소 운전 학습 | 핸들을 1~2초마다 ±1° 정도 좌우로 조금씩 움직이기 (미세 보정) |
| 6 | 졸음 운전 재현 | 핸들을 가만히 두다가 가끔 6° 이상 확 꺾기, 속도를 들쭉날쭉하게 |
| 7 | 계산 과정 보기 | Logcat 필터 `tag:FATIGUE` → 2초마다 졸음 지수 계산식과 신호 값 출력 |

> Windows에서 `adb`를 찾지 못하면 `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088` 처럼 전체 경로로 실행합니다.

| 단계 | 졸음 지수 | Virtual Console 조작 예 |
|---|---|---|
| 괜찮음 | 0~39 | 5번처럼 미세 보정 유지 |
| 1단계 대화 | 40~59 | 미세 보정을 줄이고 가끔 급조작 |
| 2단계 환경 | 60~74 | 핸들을 거의 멈추고 급조작 + 속도 출렁임 |
| 3단계 휴식 | 75~ | 위 상태 지속 → 쉼터 안내 후 기어 **P** → 파워냅 |

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
| --- | --- |
| 빌드 | AGP 8.5.0 · Gradle 8.7 · Kotlin 1.9.0 · compileSdk 34 · minSdk 28 |
| Pleos SDK | Vehicle 2.3.2 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 · NaviHelper 3.0.5 |

- **권한:** 처음 설정 화면에서 마이크·위치·차량 권한을 허용합니다.
- **Virtual Console:** `adb forward tcp:8088 tcp:8088` 실행 후 http://127.0.0.1:8088/ 접속. 기어(P단) 입력에 사용합니다.
- **시연 모드:** 에뮬레이터는 핸들·속도가 변하지 않아, 기본값(`MainActivity.kt`의 `dataMode = SCENARIO`)은 시뮬레이션 패널에서 고른 졸음 상태로 주행 데이터를 만들어 냅니다. 패널에서 단계 확인, 비상 상황 재현, 초기화를 할 수 있습니다.

## 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

- **에뮬레이터:** Pleos Connect v2.1.0 (Android API 34)
- **화면 규격:** 2560 × 1440 px, 160 dpi

## APK 파일 위치

- **GitHub Release:** [v1.0](https://github.com/ddo0S2/motor_rookie/releases/tag/v1.0) — `motor_rookie_gyeot_release.apk` (release 빌드, 61.8MB)
- **저장소:** [`apk/모터루키_곁_release.apk`](apk/) (같은 파일)
