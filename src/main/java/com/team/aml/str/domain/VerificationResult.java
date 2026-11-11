package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.domain.CreatedOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 검증 결과 한 줄 (ops.verification_results, 추가만). "어느 초안(generation_id)의 어떤 본문(해시)을 검사했고 결과가 무엇인가".
 * 만들면 바뀌지 않는다 — setter·UPDATE 없음(V008 이 UPDATE·DELETE 권한 회수). created_at·created_by 는 auditing(검증 요청자).
 * passed 는 DB 가 status 로 만드는 칸이라 읽기만 한다. 게이트는 passed 가 아니라 status 로 본다(isPassed).
 */
@Entity
@Table(name = "verification_results")
public class VerificationResult extends CreatedOnlyEntity {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Column(name = "verification_id", nullable = false, updatable = false, length = 64)
    private String verificationId;      // AI가 준 번호(v-…). 승인 요청이 이 번호로 검증을 고른다

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @Column(name = "generation_id", nullable = false, updatable = false, length = 64)
    private String generationId;        // 어느 초안의 검증인가

    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;             // 몇 번 수정본을 검사했나 (1 = AI 원문). 기록용, 게이트는 안 본다

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "narrative_sha256", nullable = false, updatable = false, length = 64)
    private String narrativeSha256;     // 검사한 본문의 해시

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 32)
    private VerificationStatus status;

    @Column(name = "passed", insertable = false, updatable = false)
    private Boolean passed;             // generated column (status = 'PASSED')

    @Column(name = "verifier_version", nullable = false, updatable = false, length = 64)
    private String verifierVersion;

    @Column(name = "produced_at", nullable = false, updatable = false)
    private Instant producedAt;         // AI 가 준 검증 시각 (UTC Z)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private JsonNode payload;           // AI 응답 전체 (code_checks, llm_checks, narrative_refs)

    /** JPA 용. */
    protected VerificationResult() {}

    private VerificationResult(String verificationId, String generationId, int revisionNo,
                               String narrativeSha256, VerificationStatus status) {
        this.verificationId = verificationId;
        this.generationId = generationId;
        this.revisionNo = revisionNo;
        this.narrativeSha256 = narrativeSha256;
        this.status = status;
    }

    /** 게이트 판단에 필요한 칸만으로 만든다(저장하지 않는 값 — 도메인 테스트용). */
    public static VerificationResult of(String verificationId, String generationId, int revisionNo,
                                        String narrativeSha256, VerificationStatus status) {
        requireText(verificationId, "verificationId");
        requireText(generationId, "generationId");
        if (revisionNo < 1) {
            throw new IllegalArgumentException("revisionNo는 1 이상이어야 한다 (1 = AI 원문): " + revisionNo);
        }
        if (narrativeSha256 == null || !SHA256_HEX.matcher(narrativeSha256).matches()) {
            throw new IllegalArgumentException("narrativeSha256은 소문자 16진수 64자여야 한다");
        }
        Objects.requireNonNull(status, "status");
        return new VerificationResult(verificationId, generationId, revisionNo, narrativeSha256, status);
    }

    /** AI 검증 응답으로 저장할 한 줄을 만든다 (S5). payload 는 AI 응답 전체. */
    public static VerificationResult record(long draftId, String verificationId, String generationId, int revisionNo,
                                            String narrativeSha256, VerificationStatus status,
                                            String verifierVersion, Instant producedAt, JsonNode payload) {
        VerificationResult v = of(verificationId, generationId, revisionNo, narrativeSha256, status);
        requireText(verifierVersion, "verifierVersion");
        v.draftId = draftId;
        v.verifierVersion = verifierVersion;
        v.producedAt = Objects.requireNonNull(producedAt, "producedAt");
        v.payload = Objects.requireNonNull(payload, "payload").deepCopy();
        return v;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
    }

    /** 승인 요청을 열 수 있는 결과인가. passed 칸이 아니라 status로 본다. */
    public boolean isPassed() { return status == VerificationStatus.PASSED; }

    public String getVerificationId() { return verificationId; }
    public Long getDraftId() { return draftId; }
    public String getGenerationId() { return generationId; }
    public int getRevisionNo() { return revisionNo; }
    public String getNarrativeSha256() { return narrativeSha256; }
    public VerificationStatus getStatus() { return status; }
    public String getVerifierVersion() { return verifierVersion; }
    public Instant getProducedAt() { return producedAt; }

    /** AI 응답 전체. 복사본. */
    public JsonNode getPayload() { return payload == null ? null : payload.deepCopy(); }
}
