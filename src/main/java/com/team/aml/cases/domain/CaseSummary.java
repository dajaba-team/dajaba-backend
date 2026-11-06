package com.team.aml.cases.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.domain.CreatedOnlyEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * AI 조사 요약 한 건 (ops.case_summaries, V006 — CASES 명세 API-12·13, §7.2). 추가만 한다(UPDATE·DELETE 회수).
 * 현재 요약 = 케이스별 최신 1건. 같은 입력으로 다시 만들어도 새 행이다.
 *
 * <ul>
 *   <li>{@code payload} — AI 응답 원문(코드 검사를 전부 통과한 것만 저장)</li>
 *   <li>{@code inputSnapshot}·{@code inputSha256} — 실제로 보낸 요청과 그 해시. {@code is_current} 판정용
 *       (케이스 version 차이로 판정하지 않는다)</li>
 *   <li>{@code basedOnCaseVersion} — AI 호출 전 cases.version. 동시성 확인용이고 최신성 판정에는 쓰지 않는다</li>
 * </ul>
 * created_by 는 요약을 갱신한 담당 조사역(로그인 사용자 — CreatedOnlyEntity auditing).
 */
@Entity
@Table(name = "case_summaries")
public class CaseSummary extends CreatedOnlyEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private JsonNode payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, updatable = false)
    private JsonNode inputSnapshot;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_sha256", nullable = false, updatable = false, length = 64)
    private String inputSha256;

    @Column(name = "generator_version", nullable = false, updatable = false, length = 64)
    private String generatorVersion;          // = generation_meta.prompt_version (str_drafts 와 같은 이름)

    @Column(name = "produced_at", nullable = false, updatable = false)
    private Instant producedAt;               // AI 가 만든 시각 (UTC)

    @Column(name = "based_on_case_version", nullable = false, updatable = false)
    private long basedOnCaseVersion;

    /** JPA 용. */
    protected CaseSummary() {}

    private CaseSummary(Long caseId, JsonNode payload, CaseSummaryInput input, String generatorVersion,
                        Instant producedAt, long basedOnCaseVersion) {
        this.caseId = caseId;
        this.payload = payload;
        this.inputSnapshot = input.snapshot();
        this.inputSha256 = input.sha256();
        this.generatorVersion = generatorVersion;
        this.producedAt = producedAt;
        this.basedOnCaseVersion = basedOnCaseVersion;
    }

    /** 코드 검사를 통과한 AI 응답을 기록한다. 검사·재확인은 부르는 쪽(RefreshCaseSummary)이 먼저 한다. */
    public static CaseSummary record(Long caseId, JsonNode payload, CaseSummaryInput input, String generatorVersion,
                                     Instant producedAt, long basedOnCaseVersion) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        Objects.requireNonNull(producedAt, "producedAt");
        return new CaseSummary(caseId, payload, input, generatorVersion, producedAt, basedOnCaseVersion);
    }

    /** 지금 요약 입력이 이 요약을 만들 때 보낸 입력과 같은가 — is_current. */
    public boolean isCurrentFor(CaseSummaryInput now) {
        return inputSha256.equals(now.sha256());
    }

    public Long getCaseId() { return caseId; }
    public JsonNode getPayload() { return payload; }
    public JsonNode getInputSnapshot() { return inputSnapshot; }
    public String getInputSha256() { return inputSha256; }
    public String getGeneratorVersion() { return generatorVersion; }
    public Instant getProducedAt() { return producedAt; }
    public long getBasedOnCaseVersion() { return basedOnCaseVersion; }
}
