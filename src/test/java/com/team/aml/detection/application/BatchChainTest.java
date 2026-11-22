package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배치 자동 연쇄 보내기 (SCRUM-349) — DB 없이. 꺼져 있으면 안 보내고, 켜져 있으면 batch_run_id 만 보낸다. */
class BatchChainTest {

    private final JobPublisher publisher = mock(JobPublisher.class);

    @Test
    void 정상_켜져_있으면_룰_스캔_뒤_점수_큐에_룰_스캔_번호만_보낸다() {
        new BatchChain(publisher, true).afterRuleScan(7L);

        verify(publisher).publish(JobQueues.BATCH_SCORE, new BatchRunMessage(7L));
    }

    @Test
    void 정상_켜져_있으면_점수가_SUCCEEDED일_때만_케이스_생성_큐로() {
        BatchChain chain = new BatchChain(publisher, true);

        chain.afterScore(8L, BatchStatus.FAILED);   // 다시 보낼 실패가 남음 — 멈춤
        verify(publisher, never()).publish(any(), any());

        chain.afterScore(9L, BatchStatus.SUCCEEDED);
        verify(publisher).publish(JobQueues.BATCH_OPEN_CASES, new BatchRunMessage(9L));
    }

    @Test
    void 경계_꺼져_있으면_아무것도_보내지_않는다() {
        BatchChain chain = new BatchChain(publisher, false);

        chain.afterRuleScan(7L);
        chain.afterScore(9L, BatchStatus.SUCCEEDED);

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void 실패_브로커가_받지_않아도_예외_없이_넘어간다() {   // fail-open — 관리자가 다음 단계를 직접 실행
        when(publisher.publish(any(), any())).thenReturn(false);

        new BatchChain(publisher, true).afterRuleScan(7L);

        verify(publisher).publish(JobQueues.BATCH_SCORE, new BatchRunMessage(7L));
    }
}
