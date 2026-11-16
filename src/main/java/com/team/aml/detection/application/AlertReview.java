package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.detection.domain.AlertRepository;
import com.team.aml.detection.domain.AlertReviewDetail;
import com.team.aml.detection.domain.AlertReviewFilter;
import com.team.aml.detection.domain.AlertReviewItem;
import com.team.aml.detection.domain.AlertReviewOrder;
import com.team.aml.detection.domain.AlertReviewRepository;
import com.team.aml.detection.domain.AlertTransaction;
import com.team.aml.detection.domain.AlertTransactionOrder;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.audit.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 경보 점검 (계약서 5.7 R1~R3, SD 명세 §7, SCRUM-198). ADMIN 만 — SecurityConfig /api/v1/admin/**.
 * 상세·거래는 조회마다 access_logs(ALERT, VIEW) 한 줄 — 기록과 조회가 같이 성공·실패하도록 readOnly 가 아닌 트랜잭션(03_shared §9).
 * 없는 경보는 기록 없이 404. 목록은 기록하지 않는다(계좌는 가린 값·메타만).
 */
@Service
public class AlertReview {

    private final AlertReviewRepository reviews;
    private final AlertRepository alerts;
    private final AccessLogWriter accessLog;

    public AlertReview(AlertReviewRepository reviews, AlertRepository alerts, AccessLogWriter accessLog) {
        this.reviews = reviews;
        this.alerts = alerts;
        this.accessLog = accessLog;
    }

    @Transactional(readOnly = true)
    public Page<AlertReviewItem> list(AlertReviewFilter filter, AlertReviewOrder order, int page, int size) {
        return new PageImpl<>(reviews.search(filter, order, (long) page * size, size), PageRequest.of(page, size),
                reviews.count(filter));
    }

    /** @throws AlertNotFoundException 없는 경보 (기록 안 남김) */
    @Transactional
    public AlertReviewDetail detail(String alertNo) {
        AlertReviewDetail detail = reviews.findDetail(alertNo).orElseThrow(() -> new AlertNotFoundException(alertNo));
        accessLog.view(TargetType.ALERT, detail.item().alertId());
        return detail;
    }

    /** 경보에 묶인 거래 한 페이지. @throws AlertNotFoundException 없는 경보 (기록 안 남김) */
    @Transactional
    public Page<AlertTransaction> transactions(String alertNo, AlertTransactionOrder order, int page, int size) {
        long alertId = reviews.findId(alertNo).orElseThrow(() -> new AlertNotFoundException(alertNo));
        accessLog.view(TargetType.ALERT, alertId);
        return new PageImpl<>(alerts.findTransactions(alertId, order, (long) page * size, size),
                PageRequest.of(page, size), alerts.countTransactions(alertId));
    }
}
