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
-
