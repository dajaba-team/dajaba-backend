package com.team.aml.detection.api;

import com.team.aml.detection.application.ScoreAfterRuleScan;
import com.team.aml.shared.messaging.InAppJob;
import com.team.aml.shared.messaging.JobQueues;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배치 잇기 점수 단계의 앱 안 경로 (SCRUM-349) — DB 없이. 자기 큐만 받고, 실행기에서 큐 리스너와 같은 일을 한다. */
class ScoreChainInAppTest {

    private final ScoreAfterRuleScan scoreAfterRuleScan = mock(ScoreAfterRuleScan.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final TaskExecutor executor = submitted::add;
    private final ScoreChainInApp sut = new ScoreChainInApp(scoreAfterRuleScan, executor);

    @Test
    void 점수_큐_작업이면_실행기에서_룰_스캔_번호로_점수를_잇는다() {
        sut.on(new InAppJob(JobQueues.BATCH_SCORE, 7L));

        verify(scoreAfterRuleScan, never()).execute(anyLong());   // 보낸 스레드에선 안 돎
        submitted.get(0).run();
        verify(scoreAfterRuleScan).execute(7L);
    }

    @Test
    void 다른_큐_작업은_받지_않는다() {
        sut.on(new InAppJob(JobQueues.BATCH_OPEN_CASES, 9L));

        assertThat(submitted).isEmpty();
    }

    @Test
    void 실행기_안에서_실패해도_예외가_스레드_밖으로_안_나간다() {   // 큐면 재시도할 DB 오류 — 앱 안 경로는 로그만
        when(scoreAfterRuleScan.execute(7L)).thenThrow(new IllegalStateException("db down"));
        sut.on(new InAppJob(JobQueues.BATCH_SCORE, 7L));

        assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
    }
}
