package com.team.aml.shared.ai;

import com.team.aml.shared.ai.AiDtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-22 (미결 28) — 기동 시 health 로그. ok 면 info 한 줄, `status`·`db`·`llm` 중 하나라도 ok 가 아니면
 * (null 포함) warn 한 줄 더. 기동은 계속(예외 안 던짐). Spring 컨텍스트 없이 단위로.
 */
@ExtendWith(OutputCaptureExtension.class)
class AiHealthCheckTest {

    private static AiHealthCheck checkWith(HealthResponse h) {
        AiClient client = new AiClient() {
            @Override public ScoresResponse scores(long batchRunId, List<AlertPayload> alerts) { throw new UnsupportedOperationException(); }
            @Override public StrDraftResponse createDraft(StrDraftRequest request) { throw new UnsupportedOperationException(); }
            @Override public VerifyResponse verify(String draftId, VerifyRequest request) { throw new UnsupportedOperationException(); }
            @Override public CaseSummaryResponse caseSummary(CaseSummaryRequest request) { throw new UnsupportedOperationException(); }
            @Override public HealthResponse health() { return h; }
        };
        return new AiHealthCheck(new StaticListableBeanFactory(Map.of("aiClient", client)).getBeanProvider(AiClient.class));
    }

    @Test
    void ok면_버전까지_info_한_줄이고_degraded_경고는_없다(CapturedOutput out) {
        checkWith(new HealthResponse("ok", "ok", "ok", "a1b2c3d", "xgb-2026-11-05", "f1", "r1"))
                .run(new DefaultApplicationArguments());

        assertThat(out.getOut() + out.getErr())
                .contains("AI server health: status=ok db=ok llm=ok server=a1b2c3d model=xgb-2026-11-05 feature=f1 retrieval=r1")
                .doesNotContain("AI server health degraded");
    }

    @Test
    void db나_llm이_ok가_아니거나_null이면_warn_한_줄(CapturedOutput out) {
        checkWith(new HealthResponse("ok", "down:OperationalError", null, "a1b2c3d", "xgb-2026-11-05", "f1", null))
                .run(new DefaultApplicationArguments());

        String all = out.getOut() + out.getErr();
        assertThat(all).contains("retrieval=null");
        assertThat(all).containsPattern("WARN.*AI server health degraded: status=ok db=down:OperationalError llm=null");
    }
}
