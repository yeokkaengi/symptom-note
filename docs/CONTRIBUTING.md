# 개발 규칙

## 1. 브랜치 전략: GitHub Flow

- `main`은 **항상 빌드와 단위 테스트가 통과하는 상태**를 유지합니다.
- 작업은 `main`에서 새 브랜치를 만들어 진행합니다.

  | 종류 | 이름 | 예 |
  |---|---|---|
  | 기능 | `feat/기능명` | `feat/voice-mute` |
  | 버그 수정 | `fix/버그명` | `fix/cooldown-reset` |
  | 문서 | `docs/문서명` | `docs/contributing` |

- 작업이 끝나면 PR(Pull Request)을 열어 `main`에 병합합니다. `main`에 직접 커밋하지 않습니다.

## 2. 커밋 메시지: Conventional Commits

형식: `type: 한국어 요약`

| type | 쓰는 경우 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `test` | 테스트 추가·수정 |
| `refactor` | 동작 변화 없는 코드 정리 |
| `chore` | 빌드 설정, 형상관리 등 기타 |

예시:

```
feat: 주행 시작 시 자동으로 기록 켜기
fix: 저장 실패 시 30초 쿨다운이 풀리지 않던 문제 수정
docs: 브랜치·커밋 규칙 문서 추가
test: 수리 후 통과 횟수 경계값 테스트 추가
refactor: 조건 문장 생성 로직을 RuleStatus로 분리
chore: 줄바꿈 규칙(.gitattributes) 추가
```

> **적용 시점**: `e148598`(chore: 줄바꿈 규칙 추가) 커밋부터 이 규칙을 적용하며,
> 그 이전 커밋은 `번호. 기능: 요약` 형식입니다.

## 3. 버전: 유의적 버전(Semantic Versioning)

`MAJOR.MINOR.PATCH`

- MAJOR: 호환되지 않는 변경(예: 저장 데이터 형식 변경)
- MINOR: 호환되는 기능 추가
- PATCH: 호환되는 버그 수정

**v1.0.0 = 공모전 1차 제출본.** 버전은 `git tag -a vX.Y.Z`로 표시합니다.

## 4. 커밋 전 확인

```
./gradlew testDebugUnitTest
```

통과하지 않으면 커밋하지 않습니다.
