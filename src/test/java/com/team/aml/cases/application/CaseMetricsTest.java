package com.team.aml.cases.application;

import com.team.aml.cases.application.CaseMetrics.ConflictAction;
import com.team.aml.cases.application.CaseMetrics.SaveRejection;
import com.team.aml.shared.ai.AiClientException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 케이스 지표 이름·태그 (06_features/cases.md "케이스 지표", SCRUM-276). */
class CaseMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CaseMetrics metrics = new CaseMetrics(registry);

    private double refreshes(String result, String code) {
        Counter c = registry.find(CaseMetrics.SUMMARY_REFRESHES).tag("result", result).tag("code", code).counter();
        return c == null ? 0 : c.count();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_충돌은_action_하나만_태그로_센다() {
        metrics.concurrentUpdate(ConflictAction.UPDATE_NOTES);
        metrics.concurrentUpdate(ConflictAction.UPDATE_NOTES);
        metrics.concurrentUpdate(ConflictAction.ASSIGN);

        Counter notes = registry.get(CaseMetrics.CONCURRENT_UPDATES).tag("action", "UPDATE_NOTES").counter();
        assertThat(notes.count()).isEqualTo(2);
        assertThat(registry.get(CaseMetrics.CONCURRENT_UPDATES).tag("action", "ASSIGN").counter().count()).isEqualTo(1);
        assertThat(notes.getId().getTags()).extracting(Tag::getKey).containsExactly("action");
    }

    @Test
    void 정상_요약은_성공_AI실패_저장거부를_result와_code로_나눈다() {
        metrics.summarySaved();
        metrics.summaryAiFailed(new AiClientException("LLM_UNAVAILABLE", "down"));
        metrics.summaryAiFailed(new AiClientException("SUMMARY_CODE_CHECK_FAILED", "검사 실패"));
        metrics.summarySaveRejected(SaveRejection.VERSION_CHANGED);
        metrics.summarySaveRejected(SaveRejection.INPUT_CHANGED);

        assertThat(refreshes("SUCCESS", "NONE")).isEqualTo(1);
        assertThat(refreshes("AI_FAILED", "LLM_UNAVAILABLE")).isEqualTo(1);
        assertThat(refreshes("AI_FAILED", "SUMMARY_CODE_CHECK_FAILED")).isEqualTo(1);
        assertThat(refreshes("SAVE_REJECTED", "VERSION_CHANGED")).isEqualTo(1);
        assertThat(refreshes("SAVE_REJECTED", "INPUT_CHANGED")).isEqualTo(1);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_표에_없는_AI_코드는_AI_OTHER_AI예외가_아니면_OTHER() {
        metrics.summaryAiFailed(new AiClientException("BRAND_NEW_AI_CODE", "?"));
        metrics.summaryAiFailed(new AiClientException("not a code", "?"));
        metrics.summaryAiFailed(new IllegalStateException("CASE-2022-000057 계좌 010_8000EBD30"));

        assertThat(refreshes("AI_FAILED", "AI_OTHER")).isEqualTo(1);
        assertThat(refreshes("AI_FAILED", "OTHER")).isEqualTo(2);
    }

    @Test
    void 경계_태그_값은_고정_목록뿐이고_식별자가_없다() {   // NFR-COM-04
        metrics.summaryAiFailed(new IllegalStateException("CASE-2022-000057 15000000"));
        for (ConflictAction a : ConflictAction.values()) {
            metrics.concurrentUpdate(a);
        }
        for (SaveRejection r : SaveRejection.values()) {
            metrics.summarySaveRejected(r);
        }

        Set<String> values = registry.getMeters().stream()
                .flatMap(m -> m.getId().getTags().stream()).map(Tag::getValue).collect(Collectors.toSet());
        assertThat(values).allMatch(v -> v.matches("[A-Z][A-Z_]*"));
        assertThat(String.join(" ", values)).doesNotContain("CASE-").doesNotContain("15000000");
        assertThat(registry.getMeters()).extracting(m -> m.getId().getTags().size()).allMatch(n -> n <= 3);
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_지표가_고장나도_밖으로_던지지_않는다() {   // fail-open
        CaseMetrics broken = new CaseMetrics(new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("registry down");
            }
        });

        assertThatCode(() -> {
            broken.concurrentUpdate(ConflictAction.CLOSE);
            broken.summarySaved();
            broken.summaryAiFailed(new AiClientException("AI_TIMEOUT", "t"));
            broken.summarySaveRejected(SaveRejection.OTHER);
        }).doesNotThrowAnyException();
    }
}
