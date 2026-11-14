# 증상노트

**차에서 이상한 소리가 날 때, 그 소리와 그 순간의 차량 상태를 함께 기록하고, 반복되는 조건을 찾아 정비소에 보여 줄 리포트로 정리하고, 수리 후에는 같은 조건에서 다시 확인해 주는 앱.**

- 패키지: `ai.symptomnote` · Pleos Connect(AAOS, Android 14) 차량용 앱
- 기술 근거: `../verify-app/README.md` 탭 5 실측 결과(이 문서에서 "verify 5-x"로 표시)

## 1. APK 파일 위치

```
apk/TEAM_증상노트_release.apk
```
- `assembleRelease` 결과물(디버그 키 서명, 별도 키 없이 설치 가능). 팀명(`TEAM`)은 제출 전에 바꿉니다.

## 2. 실행 방법

1. `Pleos_Connect_v2.1.0` AVD를 실행하고 `adb devices`에 `emulator-5554 device`가 보이는지 확인합니다.
2. 설치합니다.
   ```
   adb install -r apk/TEAM_증상노트_release.apk
   ```
3. 앱 목록에서 **증상노트**를 실행합니다(또는 `adb shell am start --user 10 -n ai.symptomnote/.ui.MainActivity`).
   - 기어가 P에서 바뀌면 시스템이 주차 화면을 "주행 중에는 사용할 수 없습니다"로 가리므로, 앱이 큰 버튼 화면(**증상노트 주행 기록**)을 자동으로 띄웁니다. 앱 목록에서 직접 열 수도 있습니다. 녹음은 그동안에도 계속됩니다.
4. 처음 실행하면 권한 팝업(마이크, 알림, 차량 속도)이 뜹니다. 모두 **허용**하세요.
5. Virtual Console에서 기어를 **P**로 두고 홈의 **기록 시작**을 누릅니다.
6. 화면 순서와 시연 방법은 [`docs/demo-scenario.md`](docs/demo-scenario.md)를 보세요. 바로 결과 화면을 보려면 **설정 → 시연 데이터 불러오기**.

### 직접 빌드하기
- Gradle 8.10.2 / AGP 8.8.2 / Kotlin 2.0.0 / **JDK 17** / compileSdk 35 / targetSdk 34 / minSdk 28 / Jetpack Compose
- 의존성: Pleos Vehicle 2.3.2, Pleos TextToSpeech 3.3.1, Room 2.6.1(KSP 2.0.0-1.0.24)
- `local.properties`에 `sdk.dir=...`를 적고:
  ```
  # Git Bash
  JAVA_HOME=/c/Users/PC/.jdks/temurin-17.0.20.1 ./gradlew assembleDebug testDebugUnitTest
  # 제출용
  JAVA_HOME=/c/Users/PC/.jdks/temurin-17.0.20.1 ./gradlew assembleRelease
  ```

## 3. 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | `Pleos_Connect_v2.1.0` AVD (Android 14 / API 34, 현재 사용자 10), Vehicle Service 2.3.22 |
| 마이크 | AVD **Extended controls → Microphone → "Virtual microphone uses host audio input" 켜기**. 끄면 녹음이 거의 무음입니다(verify 트러블슈팅 7) |
| Virtual Console | 기어(P/D), 가속·브레이크, 핸들 각도, 와이퍼, 동승석 착석을 바꿀 때 사용. 기록 시작은 **P**에서만 가능 |
| 권한(실행 중 요청) | `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `android.car.permission.CAR_SPEED` |
| 권한(선언만, normal) | `FOREGROUND_SERVICE(_MICROPHONE)`, `CAR_POWERTRAIN`, `CAR_EXTERIOR_ENVIRONMENT`, `pleos.car.permission.CAR_DRIVE / CAR_WINDOWS / CAR_LIGHTS / CAR_SEATS / CONTROL_CAR_WINDOWS / TTS_SERVICE` |
| adb로 권한 미리 주기(선택) | `adb shell pm grant --user 10 ai.symptomnote android.permission.RECORD_AUDIO` (POST_NOTIFICATIONS, android.car.permission.CAR_SPEED도 같은 방식) |

## 4. 기능

| # | 기능 | 설명 | 근거 |
|---|---|---|---|
| 1 | 녹음 | 홈(기어 P)에서 "기록 시작" → 마이크 FGS에서 48kHz(실패 시 44.1kHz) 직전 10초 링 버퍼. 다른 앱이 가려도 계속, 알림 "증상노트 기록 중" | 5-3, 5-8, 5-17 |
| 2 | 주행 신호 | 속도(표준, m/s→km/h)·기어·바깥 온도(표준), 브레이크·핸들·와이퍼·방향지시등·주행거리(Pleos). 기어가 P가 아닐 때 1초 요약을 트립 단위로 저장 | 5-5, 탭 3 |
| 3 | 주행 중 수동 기록 | DO 선언 + 별도 태스크의 큰 버튼 화면. 0.25초 안에 "기록 중", 저장 성공 뒤에만 TTS "기록했어요"(오디오 포커스 요청·반납). 기록마다 직전 10초 조건 스냅샷 | 5-9, 5-21, 5-21b |
| 4 | 증상·공통 조건 | 기록을 증상으로 묶고, 조건별 포함률(기록 중) vs 기준률(평소 주행)을 비교. 포함률 75% 이상 & 2배 이상만 채택(설계값) | - |
| 5 | 자동 포착 | 공통 조건이 시작되는 순간 + 3초에 조용히 저장(조건 7초 전~3초 후 소리). 같은 증상 30초 안 재저장 없음 | 5-13, 5-20 |
| 6 | 정비소 리포트 | 증상별 한 장: 횟수·기간·공통 조건·재현 레시피·대표 소리·조건 그래프. **PDF로 저장** → `Download/SymptomNote/` | - |
| 7 | 수리 후 확인 | 수리 완료 날짜 이후 같은 조건 통과 횟수, 자동 포착 수, 수리 전 특징 대역 에너지 변화(dB, "참고 지표"), 재발 기록 분류 | 5-4 |
| 8 | 글레오 | 조회용 함수 `READ_SYMPTOM_SUMMARY`, `HOW_TO_START_RECORDING`. 녹음 중에는 글레오가 호출을 무시함 → [`docs/platform-findings.md`](docs/platform-findings.md) | 5-16, 5-19 |
| 9 | 창문 자가 점검 | 확인 창 → 동승석 착석 확인 → 창문 제어 → 녹음 → 되돌림 → 첫 점검과 비교. **실차 전제 기능** | 5-1, 5-2 |

설계값(모두 상수로 분리, "설계값, 실측으로 조정 필요" 주석): 포함률 75%, 배율 2배, 최소 기록 2개, 최소 평소 주행 30초, 속도 구간 10km/h, 핸들 15°/90°, 온도 10°C/25°C, 자동 포착 지연 3초·쿨다운 30초, 재발 판단 -6dB.

## 5. 개인정보
- 원본 소리는 기기 안(앱 내부 저장소)에만 저장하고, 녹음 중에도 직전 10초만 메모리에 둡니다.
- 기록 목록에서 기록별 삭제, 설정에서 "녹음 파일 모두 삭제"가 가능합니다.
- 사람 말소리 무음 처리와 서버·QR 공유는 [`docs/roadmap.md`](docs/roadmap.md)의 2차 계획입니다.

## 6. 단위 테스트

`./gradlew testDebugUnitTest` — 20개
- `ConditionsTest`: 속도 구간, 규칙 매칭(범주 안 OR·범주끼리 AND), 75%/2배 채택, 기록·기준 부족, 10초 창, 수리 후 통과 횟수, 문장 생성, 규칙 저장 형식
- `AutoCaptureJudgeTest`: 시작 +3초 저장, 조건이 이어져도 재저장 없음, 30초 쿨다운, 실패 시 쿨다운 해제, 예약 중 중복 없음
- `SoundAnalysisTest`: 대역 에너지 이론값(±0.2dB), Nyquist 초과 대역, -20dB 감소, 재발 분류
- `DemoPlanTest`: 시연 데이터가 의도한 공통 조건과 수리 후 -19dB를 실제로 만드는지

## 7. 프로젝트 구조

```
app/src/main/java/ai/symptomnote/
├── App.kt                    차량 신호·자동 포착 시작
├── core/                     안드로이드 의존 없는 순수 로직(단위 테스트 대상)
│   ├── Conditions.kt         조건 후보·공통 조건·통과 횟수·문장
│   ├── AutoCaptureJudge.kt   자동 포착 판단(시작 순간, 3초, 30초)
│   └── SoundAnalysis.kt      Kotlin FFT, 대역 에너지, 수리 전후 비교
├── audio/Audio.kt            링 버퍼(RingRecorder), WAV, 재생
├── vehicle/                  VehicleSignals(표준+Pleos 구독, 1초 요약), UxWatcher, Speaker(TTS+포커스), WindowChecker
├── service/                  RecordingService(마이크 FGS), RecordSaver, AutoCaptureEngine, SymptomAnalyzer
├── data/Db.kt                Room: DriveSecond, Recording(+스냅샷), Symptom
├── gleo/GleoReceiver.kt      글레오 함수
├── demo/                     시연 데이터(DemoPlan: 순수, DemoData: 저장)
└── ui/                       주차 화면(MainActivity …), 주행 화면(DrivingRecordActivity)
app/src/main/assets/schema.json   글레오 함수 등록
docs/                             platform-findings.md, roadmap.md, demo-scenario.md
```

## 8. 에뮬레이터에서 확인한 것과 한계
- 확인 결과는 최종 보고 표와 커밋 메시지를 참고하세요. 요약:
  - 가린 상태 녹음 유지(`silenced:false`), "기록 중" 표시 +38ms, 저장 +325ms, TTS 포커스 요청 `GRANTED`·onDone 뒤 반납
  - 기어 D에서 주차 화면은 시스템에 가려지고 큰 버튼 화면은 사용 가능
  - 글레오: schema.json 로드(`Loaded schema.json from ai.symptomnote`), 두 action 형태 모두 응답
- 창문 점검: 에뮬레이터에는 실제 창문이 없어 창문 소리는 녹음되지 않습니다. 제어 뒤 값 변화는 verify 5-1(11-05)에서는 없었고, 2026-11-13 이 앱 시험에서는 목표 값(5)에 도달했습니다(원인 미확인).
- 에뮬레이터 바깥 온도는 25°C 고정이라 "차가운 날" 조건은 시연 데이터에서만 보입니다.
