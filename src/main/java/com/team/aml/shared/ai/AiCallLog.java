package com.team.aml.shared.ai;

import jakarta.persistence.*;

import java.time.Instant;

/** AI HTTP 시도 한 번 = 메타데이터 한 행. 본문·created_by 없이 추가만 한다. */
@Entity
@Table(name = "ai_call_logs")
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "endpoint", nullable = false, length = 64, updatable = false)
    private String endpoint;

    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;

    @Column(name = "attempt", nullable = false, updatable = false)
    private Short attempt;

    @Column(name = "status_code", updatable = false)
    private Integer statusCode;

    @Column(name = "error_code", length = 32, updatable = false)
    private String errorCode;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private Integer durationMs;

    @Column(name = "ai_version", length = 64, updatable = false)
    private String aiVersion;

    @Column(name = "generation_id", length = 64, updatable = false)
    private String generationId;

    @Column(name = "target_type", length = 16, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 64, updatable = false)
    private String targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiCallLog() {}

    public static AiCallLog create(String endpoint, String requestId, int attempt, Integer statusCode,
                                   String errorCode, int durationMs, String aiVersion, String generationId,
                                   String targetType, String targetId) {
        AiCallLog r = new AiCallLog();
        r.endpoint = endpoint;
        r.requestId = requestId;
        r.attempt = (short) attempt;
        r.statusCode = statusCode;
        r.errorCode = errorCode;
        r.durationMs = durationMs;
        r.aiVersion = aiVersion;
        r.generationId = generationId;
        r.targetType = targetType;
        r.targetId = targetId;
        r.createdAt = Instant.now();
        return r;
    }

    public Long getId() { return id; }
    public String getEndpoint() { return endpoint; }
    public String getRequestId() { return requestId; }
    public Short getAttempt() { return attempt; }
    public Integer getStatusCode() { return statusCode; }
    public String getErrorCode() { return errorCode; }
    public Integer getDurationMs() { return durationMs; }
    public String getAiVersion() { return aiVersion; }
    public String getGenerationId() { return generationId; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public Instant getCreatedAt() { return createdAt; }
}
