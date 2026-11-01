package com.team.aml.shared.ai;

import com.team.aml.shared.ai.AiDtos.*;

import java.util.List;

/**
 * AI 서버(FastAPI) 호출 인터페이스 (레퍼런스 8.5). 규약 6절 엔드포인트 다섯 개 = 메서드 다섯 개.
 * 유스케이스는 이 인터페이스만 본다. 구현은 두 개:
 *  - MockAiClient      aml.ai.mock=true  (local/test). AI 서버 없이 고정 응답.
 *  - WebClientAiClient aml.ai.mock=false (prod, 또는 local 에서 진짜 서버 붙일 때). SCRUM-17.
 *
 * 공통 동작(구현체가 지킨다): X-Internal-Key, X-Request-Id(UUID), 화면 요청 전체 90초(재시도 포함).
 * 재시도 1회는 HTTP 5xx 여부가 아니라 코드/원인으로 — LLM_UNAVAILABLE·MODEL_UNAVAILABLE·503 DB_UNAVAILABLE,
 * 또는 연결 거부(ConnectException)만. 그 밖의 5xx·4xx·타임아웃·409 는 재시도 없이 AiClientException (규약 6.2).
 * AI 409 GENERATION_IN_PROGRESS → 409 DRAFT_IN_PROGRESS. 호출은 트랜잭션 밖에서 (레퍼런스 8.4).
 * 실제 HTTP 시도는 ai_call_logs 에 별도 트랜잭션으로 메타데이터만 기록한다. 본문 저장 금지, mock 은 기록하지 않는다.
 */
public interface AiClient {

    /** 경보 점수. 500개 넘으면 구현체가 나눠 보내고 합친다 (규약 6.2). batchRunId 는 요청 봉투(batch_run_id)로 나간다. */
    ScoresResponse scores(long batchRunId, List<AlertPayload> alerts);

    StrDraftResponse createDraft(StrDraftRequest request);

    VerifyResponse verify(VerifyRequest request);

    CaseSummaryResponse caseSummary(CaseSummaryRequest request);

    /** 기동 시 1회 호출. 실패해도 기동은 한다(경고 로그). */
    HealthResponse health();
}
