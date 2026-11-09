package com.team.aml.detection.api;

import com.team.aml.detection.application.RunRuleScan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 룰 스캔 (SD 명세 API 3). ADMIN 만(/api/v1/admin/**). */
@Tag(name = "batches")
@RestController
@RequestMapping("/api/v1/admin/batches")
public class RuleScanController {

    private final RunRuleScan runRuleScan;

    public RuleScanController(RunRuleScan runRuleScan) {
        this.runRuleScan = runRuleScan;
    }

    @Operation(summary = "룰 스캔",
            description = "그날 켜진 룰 전부를 한 트랜잭션으로 돌려 경보를 만든다(동기). 적재 전 409 DATASET_NOT_LOADED, "
                    + "데이터 기간 밖 409 DATE_OUT_OF_RANGE, 이미 성공 409 BATCH_ALREADY_DONE, 실행 중 409 BATCH_RUNNING, "
                    + "켜진 룰 없음 409 NO_ACTIVE_RULES, 룰 기준값 오류 409 INVALID_RULE_PARAMS(배치 FAILED)")
    @PostMapping("/rule-scan")
    public RuleScanResponse ruleScan(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return RuleScanResponse.from(runRuleScan.execute(date));
    }
}
