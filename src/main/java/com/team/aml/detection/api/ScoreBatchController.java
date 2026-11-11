package com.team.aml.detection.api;

import com.team.aml.detection.application.RequestScores;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 점수 배치 (SD 명세 API 4). ADMIN 만(/api/v1/admin/**). */
@Tag(name = "batches")
@RestController
@RequestMapping("/api/v1/admin/batches")
public class ScoreBatchController {

    private final RequestScores requestScores;

    public ScoreBatchController(RequestScores requestScores) {
        this.requestScores = requestScores;
    }

    @Operation(summary = "점수 요청",
            description = "그 날짜의 PENDING 경보와 다시 보낼 FAILED 경보(코드 없음·COMPUTE_ERROR)를 500개씩 AI 에 보내 점수를 저장한다(동기). "
                    + "묶음마다 커밋 — AI 호출이 실패한 묶음은 FAILED 로 남고 200 으로 건수를 돌려준다. "
                    + "다시 보낼 실패가 남으면 status FAILED, 다시 누르면 남은 것만 보낸다(성공한 날도 가능). "
                    + "룰 스캔 전 409 RULE_SCAN_NOT_DONE, 실행 중 409 BATCH_RUNNING")
    @PostMapping("/score")
    public ScoreBatchResponse score(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ScoreBatchResponse.from(requestScores.execute(date));
    }
}
