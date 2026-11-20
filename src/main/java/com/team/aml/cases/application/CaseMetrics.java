package com.team.aml.cases.application;

import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.metrics.ErrorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 케이스 지표 (06_features/cases.md "케이스 지표", 03_shared §13, SCRUM-276).
 *
 * <ul>
 *   <li>{@value #CONCURRENT_UPDATES}{action} — cases 엔드포인트의 409 CONCURRENT_UPDATE 만. 부르는 곳은 api 의 CaseConflictCounter</li>
 *   <li>{@value #SUMMARY_REFRESHES}{result, code} — AI 요약 갱신을 AI 호출 뒤부터 끝까지. 부르는 곳은 RefreshCaseSummary</li>
 * </ul>
 *
 * 태그 값은 아래 고정 값뿐이다 — case_no·사용자·계좌·금액·본문·예외 메시지는 넣지 않는다(NFR-COM-04).
 * 세다가 예외가 나도 밖으로 던지지 않는다(fail-open) — 응답·처리는 그대로 가야 한다.
 */
@Component
public class CaseMetrics {

    public static final String CONCURRENT_UPDATES = "aml.cases.concurrent.updates";
    public static final String SUMMARY_REFRESHES = "aml.cases.summary.refreshes";

    /** CONCURRENT_UPDATES 의 action. 이름은 CaseAction 과 같게, 모르는 요청은 OTHER. */
    public enum ConflictAction { ASSIGN, REASSIGN, ESCALATE, CLOSE, REFRESH_SUMMARY, UPDATE_NOTES, OTHER }

    /** SUMMARY_REFRESHES 의 result. */
    public enum SummaryResult { SUCCESS, AI_FAILED, SAVE_REJECTED }

    /** SAVE_REJECTED 의 code — 저장 트랜잭션에서 다시 본 검사 중 무엇이 막았나. */
    public enum SaveRejection { VERSION_CHANGED, INPUT_CHANGED, NOT_ASSIGNEE, NOT_EDITABLE, CASE_NOT_FOUND, OTHER }

    static final String NO_CODE = "NONE";
    static final String NOT_AI_EXCEPTION = "OTHER";

    private static final Logger log = LoggerFactory.getLogger(CaseMetrics.class);

    private final MeterRegistry registry;

    public CaseMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void concurrentUpdate(ConflictAction action) {
        increment(CONCURRENT_UPDATES, "action", action.name());
    }

    void summarySaved() {
        summary(SummaryResult.SUCCESS, NO_CODE);
    }

    /** AI 호출 실패·응답 확인 실패. AI 코드는 03_shared §2 표에 있는 것만, 아니면 AI_OTHER. AiClientException 이 아니면 OTHER. */
    void summaryAiFailed(RuntimeException e) {
        summary(SummaryResult.AI_FAILED, e instanceof AiClientException ai ? ErrorMetrics.aiCodeTag(ai.code()) : NOT_AI_EXCEPTION);
    }

    void summarySaveRejected(SaveRejection reason) {
        summary(SummaryResult.SAVE_REJECTED, reason.name());
    }

    private void summary(SummaryResult result, String code) {
        increment(SUMMARY_REFRESHES, "result", result.name(), "code", code);
    }

    private void increment(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException e) {
            log.warn("case metric failed metric={} error_type={}", name, e.getClass().getSimpleName());
        }
    }
}
