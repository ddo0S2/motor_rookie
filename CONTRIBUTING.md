# 협업 규칙 (3명)

## 1. 브랜치

```
master                  ← 항상 실행되는 상태. 직접 커밋 금지. PR로만 합치기
 ├─ feature/detect-…    ← 1번 감지        예) feature/detect-steering, feature/detect-sim-mode
 ├─ feature/talk-…      ← 2번 대화·환경    예) feature/talk-quiz, feature/talk-climate
 └─ feature/rest-…      ← 3번 휴식·파워냅  예) feature/rest-navi, feature/rest-powernap
```

**작업 시작할 때마다**
1. `master` 로 이동 → **Git > Pull** (최신 받기)
2. 새 브랜치 만들기 (오른쪽 아래 브랜치 이름 클릭 → New Branch)
3. 작업 → 자주 커밋 → Push
4. GitHub 에서 Pull Request → 팀원 1명 확인 → Merge → 브랜치 삭제

## 2. 커밋 메시지

`종류: 무엇을 했는지` 한 줄, 한국어로 짧게.

| 종류 | 언제 | 예 |
|---|---|---|
| `feat` | 기능 추가 | `feat: 핸들 각도 Callback 연결` |
| `fix` | 버그 수정 | `fix: P단에서 점수가 안 내려가던 문제` |
| `test` | 테스트 | `test: 정상 주행 오탐 테스트 추가` |
| `docs` | 문서 | `docs: SDK 확인 결과 표 업데이트` |
| `refactor` | 동작은 같고 코드 정리 | `refactor: 신호 계산을 SignalAnalyzers 로 분리` |
| `chore` | 설정·빌드 | `chore: Vehicle SDK 2.3.2 로 올림` |

## 3. 충돌을 피하는 법
- **자기 폴더만 고치기.** 담당 폴더는 [docs/architecture.md](docs/architecture.md) 참고.
- 공용 파일(`MainActivity.kt`, `build.gradle.kts`, `AndroidManifest.xml`, `ui/theme/`)을 고칠 때는 팀 채팅에 먼저 말하고, PR 을 작게.
- 매니페스트 권한 추가는 서로 겹치기 쉬우니 **추가만** 하고 다른 사람 줄은 지우지 않기.
- 충돌이 나면 Android Studio 의 Merge 창에서 양쪽을 보고 둘 다 남길지 고르기. 모르겠으면 캡처해서 공유.

## 4. PR 올리기 전 체크
- [ ] 에뮬레이터에서 실행해 봤다
- [ ] 시뮬레이션 모드에서도 동작한다
- [ ] `FatigueEngineTest` 가 통과한다 (감지 코드를 건드린 경우)
- [ ] 화면 파일 안에 `Color(0xFF…)` 를 직접 쓰지 않았다 (색은 `ui/theme` 에서만)
- [ ] 로그용 코드(`Log.d`)는 필요한 것만 남겼다

## 5. 제출 전 (11/13~11/15)
- `master` 에서 Release 빌드 → APK 를 GitHub **Releases** 에 올리기
- README 맨 위 시연 영상·APK·기획서 링크 채우기
- 심사용 계정을 저장소에 초대 (공모전 안내 확인)
