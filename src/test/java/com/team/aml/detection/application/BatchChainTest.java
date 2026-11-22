package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.InAppJob;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배치 자동 연쇄 보내기 (SCRUM-349) — DB 없이. 항상 잇는다: 큐가 받으면 큐로, 못 받으면(RabbitMQ 꺼짐·브로커 거절) 앱 안 이벤트로.
 * 끄는 건 테스트 프로필뿐(aml.batch.chain-enabled=false).
 */
class BatchChainTest {

    private final JobPublisher publisher = mock(JobPublisher.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BatchChain chain = new BatchChain(publisher, events, true);

    @Test
    void 정상_큐가_받으면_룰_스캔_번호만_점수_큐로_보내고_앱_안에선_안_돈다() {
        when(publisher.publish(any(), any())).thenReturn(true);

        chain.afterRuleScan(7L);

        verify(publisher).publish(JobQueues.BATCH_SCORE, new BatchRunMessage(7L));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void 정상_큐가_못_받으면_같은_일을_앱_안_이벤트로() {   // RabbitMQ 꺼짐(기본)·브로커 거절 — 연쇄가 끊기지 않게
        when(publisher.publish(any(), any())).thenReturn(false);

        chain.afterRuleScan(7L);
        chain.afterScore(9L, BatchStatus.SUCCEEDED);

        verify(events).publishEvent(new InAppJob(JobQueues.BATCH_SCORE, 7L));
        verify(events).publishEvent(new InAppJob(JobQueues.BATCH_OPEN_CASES, 9L));
    }

    @Test
    void 정상_점수가_SUCCEEDED일_때만_케이스_생성으로() {
        when(publisher.publish(any(), any())).thenReturn(true);

        chain.afterScore(8L, BatchStatus.FAILED);   // 다시 보낼 실패가 남음 — 멈춤
        verify(publisher, never()).publish(any(), any());
        verify(events, never()).publishEvent(any(Object.class));

        chain.afterScore(9L, BatchStatus.SUCCEEDED);
        verify(publisher).publish(JobQueues.BATCH_OPEN_CASES, new BatchRunMessage(9L));
    }

    @Test
    void 실패_앱_실행기마저_거절해도_예외_없이_넘어간다() {   // 앞 단계 결과에 영향 없음 — 관리자가 다음 단계를 직접 실행
        when(publisher.publish(any(), any())).thenReturn(false);
        doThrow(new TaskRejectedException("full")).when(events).publishEvent(any(Object.class));

        assertThatCode(() -> chain.afterRuleScan(7L)).doesNotThrowAnyException();
    }

    @Test
    void 경계_테스트_프로필처럼_꺼져_있으면_아무것도_보내지_않는다() {
        BatchChain off = new BatchChain(publisher, events, false);

        off.afterRuleScan(7L);
        off.afterScore(9L, BatchStatus.SUCCEEDED);

        verify(publisher, never()).publish(any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
