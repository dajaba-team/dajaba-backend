package com.team.aml.shared.ai;

import com.team.aml.shared.ai.AiDtos.*;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 서버(FastAPI) 호출 인터페이스 (레퍼런스 8.5). 규약 엔드포인트 아홉 개 = 메서드 아홉 개.
 * 유스케이스는 이 인터페이스만 본다. 구현은 두 개:
 *  - MockAiClient      aml.ai.mock=true  (local/test). AI 서버 없이 고정 응답.
 *  - WebClientAiClient aml.ai.mock=false (prod, 또는 local 에서 진짜 서버 붙일 때). SCRUM-17.
 *
 * 공통 동작(구현체가 지킨다): X-Internal-Key, X-Request-Id(UUID), 화면 요청 전체 90초(재시도 포함 — 질문 답변만 30초, 참고 사례 검색만 8초, 승인 STR 색인만 30초, ML+DL 조사 순위만 10초).
 * 재시도 1회는 HTTP 5xx 여부가 아니라 코드/원인으로 — LLM_UNAVAILABLE·MODEL_UNAVAILABLE·503 DB_UNAVAILABLE,
 * 또는 연결 거부(ConnectException)만. 그 밖의 5xx·4xx·타임아웃·409 는 재시도 없이 AiClientException (규약 6.2).
 * AI 409 GENERATION_IN_PROGRESS → 409 DRAFT_IN_PROGRESS. 호출은 트랜잭션 밖에서 (레퍼런스 8.4).
 * 실제 HTTP 시도는 ai_call_logs 에 별도 트랜잭션으로 메타데이터만 기록한다. 본문 저장 금지, mock 은 기록하지 않는다.
 */
public interface AiClient {

    /** 경보 점수. 500개 넘으면 구현체가 나눠 보내고 합친다 (규약 6.2). batchRunId 는 요청 봉투(batch_run_id)로 나간다. */
    ScoresResponse scores(long batchRunId, List<AlertPayload> alerts);

    StrDraftResponse createDraft(StrDraftRequest request);

    /**
     * 검증 호출. `draftId` 는 `ai_call_logs.target_id` 에 쓰이고 요청 본문엔 **들어가지 않음** —
     * AI 쪽 `StrictModel` 이 모르는 칸이면 422. str 은 검증할 때 항상 draftId 를 알고 있다 (SCRUM-151).
     */
    VerifyResponse verify(String draftId, VerifyRequest request);

    CaseSummaryResponse caseSummary(CaseSummaryRequest request);

    /**
     * 조사역 질문 답변 (규약 5.4, SCRUM-291). **이 호출만 전체 30초**(재시도 포함, `aml.ai.ask-timeout-seconds`).
     * 상태 4종 모두 AI 200 — 상태별 칸 규칙·`case_no` 대조·`evidence_ids` ⊆ 보낸 근거가 틀리면 502 `AI_BAD_RESPONSE`.
     * `question` 은 자르지 않고 그대로 보낸다. 켜기 설정(`askEnabled`)은 부르는 쪽이 본다.
     */
    AskResponse ask(AskRequest request);

    /**
     * 참고 사례 검색 (규약 5.5, SCRUM-309). **이 호출만 전체 8초**(재시도 포함, `aml.ai.reference-search-timeout-seconds`).
     * 상태 2종 모두 AI 200 — 결과 수·점수 순서·유형 겹침·출처가 틀리면 502 `AI_BAD_RESPONSE`.
     * `draftId` 는 `ai_call_logs.target_id` 에만 쓰고 요청 본문엔 넣지 않는다(`verify` 와 같음).
     * `query` 는 부르는 쪽이 앞뒤 공백을 지우고 빈 값이면 null 로 넘긴다. 켜기 설정(`referenceSearchEnabled`)은 부르는 쪽이 본다.
     */
    ReferenceSearchResponse searchReferences(String draftId, ReferenceSearchRequest request);

    /**
     * 승인 STR 색인 (AI SCRUM-310, SCRUM-357). **이 호출만 전체 30초**(재시도 포함, `aml.ai.str-index-timeout-seconds`).
     * 상태 2종 모두 AI 200 — `report_no` 대조·상태별 칸 규칙·청크 수 ≤ 보낸 섹션 수가 틀리면 502 `AI_BAD_RESPONSE`.
     * 초안 번호는 요청 `draftId` 그대로 `ai_call_logs.target_id` 에 쓴다. 승인 뒤(커밋 뒤) 트랜잭션 밖에서 부르고,
     * 실패·SKIPPED 여도 승인은 그대로 — 예외 처리와 켜기 설정(`strIndexEnabled`)은 부르는 쪽이 본다.
     */
    StrIndexResponse indexApprovedStr(StrIndexRequest request);

    /**
     * ML+DL 조사 순위 (규약 6절 v0.4, AI #103, SCRUM-376). `GET /v1/dl-rankings/{scan_date}` — 본문 없음, 날짜는 ISO 로 경로에.
     * **이 호출만 전체 10초**(재시도 포함, `aml.ai.dl-ranking-timeout-seconds`). 재시도는 연결 단계 실패만 1회 — 503
     * `DL_RANKING_UNAVAILABLE`(스냅샷 문제)은 다시 불러도 같아 0회, 코드 그대로 502. 날짜·경보 번호 모양·순위 1…k·배열 순서·
     * 건수 합이 틀리면 502 `AI_BAD_RESPONSE`. 그 날짜 DB 경보·점수와 맞춰 보는 일은 부르는 쪽 몫(이 메서드는 DB 를 안 봄).
     * 기존 채점·케이스 생성과 별개 — 그 흐름에서 부르지 않는다. 날짜는 `ai_call_logs.target_id` 에 쓴다.
     */
    DlRankingResponse getDlRankings(LocalDate scanDate);

    /** 기동 시 1회 호출. 실패해도 기동은 한다(경고 로그). */
    HealthResponse health();
}
