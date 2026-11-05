# contracts/examples — AI 리포와 같은 파일

AI 리포 `dajaba-AI/contracts/examples/` 와 **바이트 그대로 같은** 예시 JSON. `AiDtos` 왕복 테스트(`AiExamplesContractTest`)가 이 파일로 요청·응답 모양을 확인하고, 모르는 칸·빠진 칸이 없는지 본다.

## 규칙 (SCRUM-151, 11/6)

- AI 리포 쪽이 정본. 이 쪽은 복사본.
- 바꿀 때는 **양쪽 리포에 같이 복사** — 한쪽만 바꾸면 왕복 테스트가 바로 깨진다(의도).
- 복사 기준 커밋: AI 리포 `develop` 중 `contracts/examples/` 를 바꾼 가장 최근 머지(현재 `80d988c` = SCRUM-144 머지 뒤).
- 공백·줄바꿈까지 그대로. 재포맷 금지.

## 포함 파일

| 파일 | 규약 자리 | 쓰는 테스트 |
|---|---|---|
| `draft_request.json` | §5.1 초안 요청 | `AiExamplesContractTest.draft_request_roundtrip` |
| `draft_response.json` | §5.2 초안 응답 (narrative v1 예시) | `AiExamplesContractTest.draft_response_fields_populated_and_raw_has_original` |
| `verify_request.json` | §5.3 검증 요청 (`draftId` 는 본문에 없음 — 인자로) | `AiExamplesContractTest.verify_request_roundtrip_has_no_draft_id_field` |
| `verify_response.json` | §5.3 검증 응답 — CODE_FAILED 예시 | `AiExamplesContractTest.verify_response_code_failed_fields_populated` |
| `verify_response_passed.json` | §5.3 검증 응답 — PASSED 예시 (narrative v2 7섹션 + narrative_refs·judge_meta 실측) | `AiExamplesContractTest.verify_response_passed_fields_populated` |
| `score_request.json` | §2 경보 JSON 묶음 — `AlertPayload` 모양 (`created_at` 포함) | `AlertPayloadScoreRequestTest` |
| `narrative_hash.json` | 본문 해시 테스트 벡터 (v1) | `NarrativeHashTest` |
