package com.team.aml.shared.batch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface BatchRunRepository extends JpaRepository<BatchRun, Long> {

    /** 같은 유형·날짜가 지금 돌고 있나 (11.1: RUNNING 이면 409 BATCH_RUNNING). */
    Optional<BatchRun> findFirstByBatchTypeAndBatchDateAndStatus(BatchType type, LocalDate date, BatchStatus status);

    /** 같은 유형·날짜가 이미 성공했나 (11.1: SUCCEEDED 면 409 BATCH_ALREADY_DONE — 안건 21 에서 범위 조정 예정). */
    boolean existsByBatchTypeAndBatchDateAndStatus(BatchType type, LocalDate date, BatchStatus status);

    /** 그 시각 뒤에 시작된 같은 유형·날짜 실행이 있나 — 배치 잇기가 같은 메시지를 두 번 처리하지 않게 (SCRUM-349). */
    boolean existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(BatchType type, LocalDate date, Instant startedAt);
}
