package com.team.aml.shared.ai;

import com.team.aml.shared.ai.AiDtos.*;

import java.util.List;

/**
 * AI 서버(FastAPI) 호출 인터페이스 (레퍼런스 8.5). 규약 6절 엔드포인트 다섯 개 = 메서드 다섯 개.
 * 유스케이스는 이 인터페이스만 본다. 구현은 두 개:
 *  - MockAiClient      aml.ai.mock=true  (local/test). AI 서버 없이 고정 응답.
 *  - WebClientAiClient aml.ai.mock=false (prod, 또는 local 에서 진짜 서버 붙일 때). W3 에 진희가 채운다.
 *
 * 공통 동작(구현체가 지킨다): X-Internal-Key, X-Request-Id(UUID = ai_call_logs.request_id), 90초 타임아웃,
 * 5xx·타임아웃에 1회 재시도(규약 6.2 · 레퍼런스 8.5), 4xx 는 재시도 없이 AiClientException, 모든 호출 ai_call_logs 기록.
 * 호출은 반드시 트랜잭션 밖에서 (레퍼런스 8.4).
 */
public interface AiClient {

    /** 경보 점수. 500개 넘으면 구현체가 나눠 보내고 합친다 (규약 6.2). */
    ScoresResponse scores(List<AlertPayload> alerts);

    StrDraftResponse createDraft(StrDraftRequest request);

    VerifyResponse verify(VerifyRequest request);

    CaseSummaryResponse caseSummary(CaseSummaryRequest request);

    /** 기동 시 1회 호출. 실패해도 기동은 한다(경고 로그). */
    HealthResponse health();
}
