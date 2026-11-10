package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertRepository;
import com.team.aml.detection.domain.AlertTransaction;
import com.team.aml.detection.domain.AlertTransactionOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 경보에 묶인 거래 공개 조회 (CASES 명세 6.1 ListAlertTransactions, SCRUM-179). 의존 방향 cases → detection.
 * 경보가 있는지는 부르는 쪽(케이스는 alert_id FK 가 있음)이 안다 — 여기서는 묶인 거래만 돌려준다.
 */
@Service
@Transactional(readOnly = true)
public class ListAlertTransactions {

    public record Page(List<AlertTransaction> items, long total) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private final AlertRepository alerts;

    public ListAlertTransactions(AlertRepository alerts) {
        this.alerts = alerts;
    }

    public Page page(long alertId, AlertTransactionOrder order, long offset, int limit) {
        return new Page(alerts.findTransactions(alertId, order, offset, limit), alerts.countTransactions(alertId));
    }

    /** 전체, 시각·id 순. 합계(aggregates)용 — 경보 하나의 거래라 수가 크지 않다. */
    public List<AlertTransaction> all(long alertId) {
        return alerts.findAllTransactions(alertId);
    }

    /** 이 경보의 거래가 아니면 empty. */
    public Optional<AlertTransaction> one(long alertId, long transactionId) {
        return alerts.findTransaction(alertId, transactionId);
    }
}
