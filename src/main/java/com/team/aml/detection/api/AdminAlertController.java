package com.team.aml.detection.api;

import com.team.aml.detection.application.AlertReview;
import com.team.aml.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 경보 점검 (계약서 5.7 R1~R3, SCRUM-198). ADMIN 만 — 조사역·승인자는 케이스 상세에서 관련 경보만 본다(11/3). */
@Tag(name = "admin-alerts")
@RestController
@RequestMapping("/api/v1/admin/alerts")
public class AdminAlertController {

    private final AlertReview review;

    public AdminAlertController(AlertReview review) {
        this.review = review;
    }

    @Operation(summary = "경보 목록 (R1)",
            description = "필터는 전부 선택·AND. 정렬 alert_no·score·created_at, 기본 score,desc — 점수 없는 경보는 맨 뒤. "
                    + "FAILED 도 남는다(0점 아님). retryable = 점수 배치를 다시 돌리면 다시 보내는 실패. 열람 기록 안 남김")
    @GetMapping
    public PageResponse<AdminAlertListItemResponse> list(@Valid @ParameterObject @ModelAttribute AdminAlertListQuery q) {
        return PageResponse.from(review.list(q.toFilter(), q.order(), q.pageOrDefault(), q.sizeOrDefault())
                .map(AdminAlertListItemResponse::from));
    }

    @Operation(summary = "경보 상세 (R2)", description = "열람 기록을 남긴다. 없으면 404 ALERT_NOT_FOUND. 점수가 없으면 latest_score null")
    @GetMapping("/{alertNo}")
    public AdminAlertDetailResponse detail(@PathVariable String alertNo) {
        return AdminAlertDetailResponse.from(review.detail(alertNo));
    }

    @Operation(summary = "경보 근거 거래 (R3)",
            description = "열람 기록을 남긴다. 정렬 ts·transaction_id, 기본 ts,asc. 계좌는 가린 값 + account_ref")
    @GetMapping("/{alertNo}/transactions")
    public PageResponse<AdminAlertTransactionResponse> transactions(
            @PathVariable String alertNo, @Valid @ParameterObject @ModelAttribute AdminAlertTransactionQuery q) {
        return PageResponse.from(review.transactions(alertNo, q.order(), q.pageOrDefault(), q.sizeOrDefault())
                .map(AdminAlertTransactionResponse::from));
    }
}
