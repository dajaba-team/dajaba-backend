package com.team.aml.str.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 검증 결과 한 줄. "어느 초안(generation_id)의 어떤 본문(해시)을 검사했고 결과가 무엇인가".
 * 만들면 바뀌지 않는다. JPA 매핑은 V008 때 붙인다.
 */
public class VerificationResult {
    private final String verificationId;      // AI가 준 번호(v-…). 승인 요청이 이 번호로 검증을 고른다
    private final String generationId;        // 어느 초안의 검증인가
    private final int revisionNo;             // 몇 번 수정본을 검사했나 (1 = AI 원문). 기록용, 게이트는 안 본다
    private final String narrativeSha256;     // 검사한 본문의 해시
    private final VerificationStatus status;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private VerificationResult(String verificationId, String generationId, int revisionNo,
                               String narrativeSha256, VerificationStatus status) {
        this.verificationId = verificationId;
        this.generationId = generationId;
        this.revisionNo = revisionNo;
        this.narrativeSha256 = narrativeSha256;
        this.status = status;
    }

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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
    }

    /** 승인 요청을 열 수 있는 결과인가. passed 칸이 아니라 status로 본다. */
    public boolean isPassed() { return status == VerificationStatus.PASSED; }

    public String getVerificationId() { return verificationId; }
    public String getGenerationId() { return generationId; }
    public int getRevisionNo() { return revisionNo; }
    public String getNarrativeSha256() { return narrativeSha256; }
    public VerificationStatus getStatus() { return status; }

}
