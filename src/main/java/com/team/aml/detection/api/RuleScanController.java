package com.team.aml.detection.api;

import com.team.aml.detection.application.RunRuleScan;
import com.team.aml.shared.batch.BatchRunView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 룰 스캔 (SD 명세 API 3). ADMIN 만(/api/v1/admin/**). 진행 상태·결과는 GET /api/v1/admin/batches/{id} (SCRUM-349). */
@Tag(name = "batches")
@RestController
@RequestMapping("/api/v1/admin/batches")
public class RuleScanController {

    private final RunRuleScan runRuleScan;

    public RuleScanController(RunRuleScan runRuleScan) {
        this.runRuleScan = runRuleScan;
    }

    @Operation(summary = "룰 스캔",
            description = "202 로 접수하고 RUNNING 상태의 배치를 돌려준 뒤, 그날 켜진 룰 전부를 뒤에서 한 트랜잭션으로 돌려 경보를 만든다. "
                    + "결과는 배치 조회의 summary.alerts_by_rule(룰별 경보 수). 적재 전 409 DATASET_NOT_LOADED, "
                    + "데이터 기간 밖 409 DATE_OUT_OF_RANGE, 이미 성공 409 BATCH_ALREADY_DONE, 실행 중 409 BATCH_RUNNING, "
                    + "켜진 룰 없음 409 NO_ACTIVE_RULES. 룰 기준값 오류(INVALID_RULE_PARAMS)는 스캔 중이라 배치 FAILED·error_message 로 남는다")
    @PostMapping("/rule-scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BatchRunView ruleScan(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return runRuleScan.execute(date);
    }
}
