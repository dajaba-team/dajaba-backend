## ⭐ Key Changes
> 핵심 변경 사항을 적어주세요.
1.
2.
3.

<br>

## ✅ 확인 방법
> 리뷰어가 직접 돌려볼 수 있도록 실행 방법을 적어주세요. (swagger 호출 순서, curl, 테스트 이름 등)
-

<br>

## 👪 To Reviewers
>

<br>

## 📎 참조한 문서 · 규칙
> 구현 전 읽은 문서와 따른 절 번호를 적어주세요.
> 예: 백엔드 설계 가이드 §4 상태 전이 · 레퍼런스 3절 · 인터페이스 규약 v0.2 / Flyway `V005__create_detection.sql`
-

<br>

## 📋 체크리스트
- [ ] **PR 대상이 `develop`이다** (`main`은 develop에서만 올린다)
- [ ] **로컬에서 `./gradlew test` 통과**. 실행 OS와 결과(`ArchitectureTest` pass, `ApplicationContextTest` pass/skip(Docker 없음))를 위 확인 방법에 적었다
- [ ] GitHub Actions **CI가 초록불**이다 (빨간불이면 Details → 실패한 테스트부터 확인)
- [ ] PR은 한 가지 일만 · 목표 ≤400줄 (생성 파일 제외)
- [ ] 테스트 포함 — 최소 정상 1 + 경계 1 + 실패 1
- [ ] (DB 변경 시) Flyway 새 파일 1개만 — `V{번호}__{설명}.sql`, 번호는 팀 채팅에 예약했고, 이미 적용된 파일은 고치지 않았다
- [ ] 업무 규칙은 엔티티 안에 — `setStatus()` 없이 `case.escalate()` 같은 메서드로, `@Transactional`은 application에만
- [ ] 다른 기능 폴더는 application 유스케이스로만 불렀다 (`source ← detection ← cases ← str`)
- [ ] 로그인 사용자는 `CurrentUser`로 꺼냈다 (세션·SecurityContext 직접 접근 없음)
- [ ] (API 추가·변경 시) 에러는 `GlobalExceptionHandler` 형식, 목록은 `PageResponse`, swagger에서 직접 호출해 봤다
- [ ] 하드코딩 없음 — 비밀값은 `.env`/환경변수, 설정은 `application.yml`(`AmlProperties`)
- [ ] `TODO`에 안건 번호를 달았다 (`// TODO(Open-11):`)
- [ ] (새 라이브러리 추가 시) 3줄 사유 — 유지보수 · 무게 · 라이선스
