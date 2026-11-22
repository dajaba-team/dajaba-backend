package com.team.aml.cases.api;

import com.team.aml.cases.application.OpenCasesAfterScore;
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

/** 배치 잇기 케이스 생성 단계의 앱 안 경로 (SCRUM-349) — DB 없이. 자기 큐만 받고, 실행기에서 큐 리스너와 같은 일을 한다. */
class OpenCasesChainInAppTest {

    private final OpenCasesAfterScore openCasesAfterScore = mock(OpenCasesAfterScore.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final TaskExecutor executor = submitted::add;
    private final OpenCasesChainInApp sut = new OpenCasesChainInApp(openCasesAfterScore, executor);

    @Test
    void 케이스_생성_큐_작업이면_실행기에서_점수_번호로_케이스_생성을_잇는다() {
        sut.on(new InAppJob(JobQueues.BATCH_OPEN_CASES, 9L));

        verify(openCasesAfterScore, never()).execute(anyLong());
        submitted.get(0).run();
        verify(openCasesAfterScore).execute(9L);
    }

    @Test
    void 다른_큐_작업은_받지_않는다() {
        sut.on(new InAppJob(JobQueues.BATCH_SCORE, 7L));

        assertThat(submitted).isEmpty();
    }

    @Test
    void 실행기_안에서_실패해도_예외가_스레드_밖으로_안_나간다() {
        when(openCasesAfterScore.execute(9L)).thenThrow(new IllegalStateException("db down"));
        sut.on(new InAppJob(JobQueues.BATCH_OPEN_CASES, 9L));

        assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
    }
}
