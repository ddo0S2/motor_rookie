# Pleos SDK 확인 결과

에뮬레이터: Pleos Connect v2.1.0 (API 34) · Vehicle SDK 2.3.2
확인한 사람과 날짜를 같이 적습니다. 실패하면 에러 메시지를 그대로 붙여 넣습니다.

| 기능 | 담당 | API (실제 함수 이름) | 방식 | 권한 | 결과 | 비고 |
|---|---|---|---|---|---|---|
| 운전석 문 상태 | 1번 | `getDoor().getCurrentDoorPosition` | Get | `CAR_DOORS` | ✅ 성공 (닫힘=0) | 10/28 |
| 핸들 각도 | 1번 | | Callback | | | |
| 차량 속도 | 1번 | | Callback | | | |
| 기어 | 1번 | | Callback | | | |
| 와이퍼 | 1번 | | Callback | | | 비·눈 가중치 |
| 순정 경고 (FCW/LDW/DAW) | 1번 | | Callback | | | `s_signal` |
| STT | 2번 | | | | | |
| TTS | 2번 | | | | | |
| 설정 온도 | 2번 | `getHvac().setHvacTemperature` | Set | | | |
| 송풍 세기 | 2번 | `getHvac().setHvacFanSpeed` | Set | | | |
| 창문 | 3번 | | Set | | | |
| 졸음쉼터 검색·경유지 | 3번 | NaviHelper | | | | |
| 시트 등받이 | 3번 | | Get/Set | | | P단에서만 |
| 실내 조명 | 3번 | | Get/Set | | | |

## adb 로 값 넣어 보기 (에뮬레이터)

```
adb shell cmd car_service get-property-value <번호>
adb shell cmd car_service inject-vhal-event <번호> <area> <값>
```

| 값 | 표준 번호 (확인 필요) |
|---|---|
| 차량 속도 | `0x11600207` |
| 핸들 각도 | `0x11600209` |
| 기어 | `0x11400400` (P=4, D=8) |
| 설정 온도 | `0x15600503` |
| 운전석 문 | `0x16400B00` |
