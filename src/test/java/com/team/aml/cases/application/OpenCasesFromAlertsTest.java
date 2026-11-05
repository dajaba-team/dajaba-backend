package com.team.aml.cases.application;

import com.team.aml.cases.domain.CasePolicy;
import com.team.aml.cases.domain.PolicyNotConfiguredException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 없이 — 정책이 비어 있으면 batch_runs 행을 만들기 전에 거절한다 (recorder 를 null 로 둬서 안 불렸음을 확인). */
class OpenCasesFromAlertsTest {

    @Test
    void 실패_정책이_비어_있으면_배치를_시작하지_않는다_POLICY_NOT_CONFIGURED() {
        OpenCasesFromAlerts batch = new OpenCasesFromAlerts(null, null, null,
                () -> CasePolicy.of(null, null, null), null);

        assertThatThrownBy(() -> batch.start(LocalDate.of(2022, 9, 3), 1L))
                .isInstanceOf(PolicyNotConfiguredException.class);
    }
}
