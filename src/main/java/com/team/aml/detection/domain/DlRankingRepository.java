package com.team.aml.detection.domain;

import java.time.LocalDate;
import java.util.List;

/** ML+DL 조사 순위 대조 읽기 (SCRUM-376). 읽기만. */
public interface DlRankingRepository {

    /** 그 날짜(`batch_date`) 경보 전부 — 번호·룰·점수·모델을 쿼리 하나로. 경보 번호 순. */
    List<DlRankingAlertRow> findByBatchDate(LocalDate batchDate);
}
