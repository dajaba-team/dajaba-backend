package com.team.aml.detection.domain;

import java.util.List;
import java.util.Optional;

/** 관리자 경보 점검 조회 (계약서 5.7 R1·R2, SCRUM-198). 읽기만. 거래(R3)는 AlertRepository.findTransactions 를 그대로 쓴다. */
public interface AlertReviewRepository {

    List<AlertReviewItem> search(AlertReviewFilter filter, AlertReviewOrder order, long offset, int limit);

    long count(AlertReviewFilter filter);

    /** 화면 번호로 상세. 없으면 empty. */
    Optional<AlertReviewDetail> findDetail(String alertNo);

    /** 화면 번호 → DB id. 없으면 empty. */
    Optional<Long> findId(String alertNo);
}
