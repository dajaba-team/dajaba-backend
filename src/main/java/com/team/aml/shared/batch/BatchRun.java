package com.team.aml.shared.batch;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 배치 실행 한 번 = 한 행 (레퍼런스 3.2, 11.1). 네 배치가 전부 이걸 쓴다 — 적재(source)·룰 스캔·점수(detection)·케이스 생성(cases).
 * 그래서 detection 이 아니라 shared 에 있다 (10/22 안건 14). created_at 만 있고 updated_* 없음 → BaseEntity 상속 안 함.
 *
 * 쓰는 법: BatchRun.start(type, date, userId) → 저장 → 일 끝나면 succeed(rowsIn, rowsOut) 또는 fail(message) → 저장.
 */
@Entity
@Table(name = "batch_runs")
public class BatchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_type", nullable = false, length = 32)
    private BatchType batchType;

    @Column(name = "batch_date", nullable = false)
    private LocalDate batchDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "rows_in")
    private Integer rowsIn;

    @Column(name = "rows_out")
    private Integer rowsOut;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "triggered_by", nullable = false)
    private Long triggeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BatchRun() {}

    public static BatchRun start(BatchType type, LocalDate date, long triggeredBy) {
        BatchRun r = new BatchRun();
        r.batchType = type;
        r.batchDate = date;
        r.status = BatchStatus.RUNNING;
        r.startedAt = Instant.now();
        r.createdAt = r.startedAt;
        r.triggeredBy = triggeredBy;
        return r;
    }

    public void succeed(int rowsIn, int rowsOut) {
        this.status = BatchStatus.SUCCEEDED;
        this.rowsIn = rowsIn;
        this.rowsOut = rowsOut;
        this.finishedAt = Instant.now();
    }

    public void fail(String message) {
        this.status = BatchStatus.FAILED;
        this.errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 4000));
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public BatchType getBatchType() { return batchType; }
    public LocalDate getBatchDate() { return batchDate; }
    public BatchStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Integer getRowsIn() { return rowsIn; }
    public Integer getRowsOut() { return rowsOut; }
    public String getErrorMessage() { return errorMessage; }
    public Long getTriggeredBy() { return triggeredBy; }
}
