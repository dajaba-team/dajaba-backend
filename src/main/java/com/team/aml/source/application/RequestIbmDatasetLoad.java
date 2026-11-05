package com.team.aml.source.application;

import com.team.aml.shared.security.CurrentUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 적재 요청 (SD 명세 API 1). RUNNING 을 커밋한 뒤 적재는 뒤에서 돌리고 바로 돌아온다 → 202.
 * 비동기 스레드엔 세션이 없어서 요청자 id 는 여기서 꺼내 넘긴다.
 */
@Service
public class RequestIbmDatasetLoad {

    private final LoadIbmDataset loadIbmDataset;
    private final CurrentUser currentUser;
    private final TaskExecutor executor;

    /** Spring Boot 기본 작업 실행기. 적재는 한 번에 하나라 따로 만들지 않는다. */
    public RequestIbmDatasetLoad(LoadIbmDataset loadIbmDataset, CurrentUser currentUser,
                                 @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.loadIbmDataset = loadIbmDataset;
        this.currentUser = currentUser;
        this.executor = executor;
    }

    /** 파일 없음·이미 적재·실행 중이면 여기서 409 를 던지고, 접수되면 batch_run_id 를 돌려준다. */
    public long execute() {
        long batchRunId = loadIbmDataset.start(currentUser.require().userId());
        executor.execute(() -> loadIbmDataset.run(batchRunId));
        return batchRunId;
    }
}
