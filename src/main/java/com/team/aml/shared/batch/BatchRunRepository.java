package com.team.aml.shared.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 앱이 뜰 때 남은 그 유형의 RUNNING 을 전부 FAILED 로 (단일 서버 전제, SCRUM-246). 정리한 건수.
     * 엔티티를 거치지 않는 갱신이라 BatchRunMetrics 에 세지 않는다 — 다른 Recorder 의 재시작 정리와 같다.
     */
    @Modifying
    @Query(value = """
            update ops.batch_runs set status = 'FAILED', error_message = :message, finished_at = now()
            where batch_type = :type and status = 'RUNNING'""", nativeQuery = true)
    int failRunning(@Param("type") String type, @Param("message") String message);
}
