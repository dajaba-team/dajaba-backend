package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseAction;
import com.team.aml.shared.metrics.ErrorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CASES 지표 (SCRUM-276, 03_shared §13 이름·태그 규칙, cases.md "지표").
 *
 * <ul>
 *   <li>{@code aml.cases.conflicts{action}} — 케이스 변경 API 가 409 {@code CONCURRENT_UPDATE}로 끝난 건수.
 *       다른 409(INVALID_CASE_TRANSITION 등)는 안 센다. 세는 곳은 {@code cases.api.CaseConflictCounter}.</li>
 *   <li>{@code aml.cases.summary.failures{stage, reason}} — AI 조사 요약 갱신(A7)이 저장 못 하고 끝난 건수.
 *       stage = AI_CALL(AI 호출) · RESPONSE_CHECK(응답 확인) · SAVE(저장 단계 다시 검사).</li>
 * </ul>
 *
 * 태그 값은 정해진 것만 — 케이스 번호·사용자·예외 메시지는 넣지 않는다(NFR-COM-04). 목록·상세 응답 시간은
 * 공통 HTTP 지표({@code http_server_requests_seconds}, uri 템플릿)로 충분해서 따로 만들지 않는다.
 * 세다가 예외가 나도 밖으로 던지지 않는다(fail-open) — 원래 응답·예외가 그대로 나가야 한다.
 */
@Component
public class CaseMetrics {

    public static final String CONFLICTS = "aml.cases.conflicts";
    public static final String SUMMARY_FAILURES = "aml.cases.summary.failures";

    /** 요약 실패 단계. */
    public enum SummaryStage { AI_CALL, RESPONSE_CHECK, SAVE }

    /** 저장 단계(트랜잭션 안 다시 검사)에서 막힌 이유. AI 를 기다리는 사이 바뀐 것. */
    public enum SaveFailure {
        /** 케이스 version 이 화면이 읽은 값과 다름(그사이 다른 변경) 또는 커밋 때 @Version 충돌 */
        VERSION_MISMATCH,
        /** 요약 입력(경보·점수·근거) 해시가 AI 에 보낸 것과 다름 */
        INPUT_HASH_MISMATCH,
        /** 그사이 담당자가 바뀜(403 NOT_ASSIGNEE) */
        ASSIGNEE_CHANGED,
        /** 그사이 편집할 수 없는 상태가 됨(409 INVALID_CASE_TRANSITION, 종결 등) */
        STATE_CHANGED
    }

    /** AI_CALL·RESPONSE_CHECK 의 reason — 03_shared §2 표의 AI 코드({@link ErrorMetrics#isAiCode}, 목록은 거기 한 곳)는 그대로, 그 밖은 이것. */
    static final String AI_OTHER = "AI_OTHER";

    private static final Logger log = LoggerFactory.getLogger(CaseMetrics.class);

    private final MeterRegistry registry;

    public CaseMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 케이스 변경 API 하나가 409 CONCURRENT_UPDATE 로 끝났다. */
    public void conflict(CaseAction action) {
        increment(CONFLICTS, "action", action.name());
    }

    /** AI 호출이 실패했다(AiClientException 코드, 재시도 다 쓴 뒤). */
    public void summaryAiFailed(String code) {
        increment(SUMMARY_FAILURES, "stage", SummaryStage.AI_CALL.name(), "reason", aiReason(code));
    }

    /** AI 는 답했지만 저장할 수 없는 응답이었다(AI_BAD_RESPONSE·SUMMARY_CODE_CHECK_FAILED). */
    public void summaryRejected(String code) {
        increment(SUMMARY_FAILURES, "stage", SummaryStage.RESPONSE_CHECK.name(), "reason", aiReason(code));
    }

    /** 저장 트랜잭션에서 다시 검사하다 막혔다. */
    public void summarySaveFailed(SaveFailure reason) {
        increment(SUMMARY_FAILURES, "stage", SummaryStage.SAVE.name(), "reason", reason.name());
    }

    static String aiReason(String code) {
        return ErrorMetrics.isAiCode(code) ? code : AI_OTHER;
    }

    private void increment(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException e) {
            log.warn("case metric failed metric={} error_type={}", name, e.getClass().getSimpleName());
        }
    }
}
