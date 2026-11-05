package com.team.aml.source.api;

import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.source.application.RequestIbmDatasetLoad;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** IBM HI-Small 적재 (SD 명세 API 1). ADMIN 만, 진행 상태는 GET /api/v1/admin/batches/{id}. */
@Tag(name = "batches")
@RestController
@RequestMapping("/api/v1/admin/batches")
public class DatasetLoadController {

    private final RequestIbmDatasetLoad requestLoad;
    private final BatchRunQuery batchRuns;

    public DatasetLoadController(RequestIbmDatasetLoad requestLoad, BatchRunQuery batchRuns) {
        this.requestLoad = requestLoad;
        this.batchRuns = batchRuns;
    }

    @Operation(summary = "IBM 데이터 적재",
            description = "202 로 접수하고 뒤에서 적재한다. 파일 없음 409 DATASET_FILES_MISSING, "
                    + "이미 적재 409 BATCH_ALREADY_DONE, 실행 중 409 BATCH_RUNNING")
    @PostMapping("/load")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BatchRunView load() {
        return batchRuns.get(requestLoad.execute());
    }
}
