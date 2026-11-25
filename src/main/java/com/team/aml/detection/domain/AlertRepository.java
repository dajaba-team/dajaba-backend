package com.team.aml.detection.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 경보 조회·상태 변경. 경보는 만든 뒤 score_status·score_failure_code·case_opened 만 바뀐다 (V005). */
public interface AlertRepository {

    /** 그 날짜의 SCORED·case_opened = false 경보와 점수. 경보 id 순. */
    List<AlertForCaseOpening> findForCaseOpening(LocalDate batchDate);

    /** case_opened 를 false → true 로 바꾼다. 바뀐 행이 없으면 false. */
    boolean markCaseOpened(long alertId);

    boolean exists(long alertId);

    /** 경보 id 여럿의 번호·배치 날짜. 없는 id 는 빠진다. ids 는 비우지 않고 넘긴다 (SCRUM-420). */
    List<AlertKey> findKeys(Collection<Long> alertIds);

    /** 초안 요청용 경보 원문 + reasons + 같은 법인의 앞선 경보 수. 없으면 empty. */
    Optional<AlertForDraft> findForDraft(long alertId);

    /**
     * 같은 법인에서 reference_ts 가 before 보다 앞선 경보, 최근 것부터 limit 건 (케이스 상세 prior_alerts, SCRUM-239).
     * 조건은 findForDraft 의 앞선 경보 수와 같다.
     */
    List<PriorAlert> findPriorByEntity(String entityId, LocalDateTime before, int limit);

    /** 케이스 상세용 경보 + 점수 + 거래 수. 없으면 empty. */
    Optional<AlertSummary> findSummary(long alertId);

    /** 경보에 묶인 거래 한 페이지 (케이스 근거 거래 C3·그래프 C5). */
    List<AlertTransaction> findTransactions(long alertId, AlertTransactionOrder order, long offset, int limit);

    /** 경보에 묶인 거래 전체, 시각·id 순 (케이스 상세 aggregates). */
    List<AlertTransaction> findAllTransactions(long alertId);

    long countTransactions(long alertId);

    /** 이 경보에 묶인 거래면 그 한 건, 아니면 empty (C4 — 다른 경보의 거래는 없는 것과 같다). */
    Optional<AlertTransaction> findTransaction(long alertId, long transactionId);
}
