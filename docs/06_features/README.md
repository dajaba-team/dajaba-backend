# 06. 기능별 정리

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

총괄 리뷰를 하려면 남의 폴더가 무엇을 하는지 알아야 해서, 기능별로 **담당 · 테이블 · 유스케이스 · API · 상태 · 다른 기능과 맞물리는 곳**을 모았어요. 각자 자기 파일만 고치면 되도록 담당자별로 나눴어요.

| 파일 | 기능 | 담당 |
|---|---|---|
| [`source_detection.md`](source_detection.md) | source(적재) · detection(룰 · 경보 · 점수) | 준혁 |
| [`cases.md`](cases.md) | cases(조사 · 상태 전이 · CasePolicy · 메모 · 요약) | 지윤 |
| [`str.md`](str.md) | str(초안 · 수정 · 검증 · 승인 · 반려) | 채영 |

user · shared(진희)는 [`../03_shared.md`](../03_shared.md)(공통 코드)와 [`../04_auth_user.md`](../04_auth_user.md)(계정 · 권한 · 로그인)에 있어요.
