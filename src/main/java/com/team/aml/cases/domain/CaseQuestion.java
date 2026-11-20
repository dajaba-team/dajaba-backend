package com.team.aml.cases.domain;

import com.team.aml.shared.domain.CreatedOnlyEntity;
import com.team.aml.shared.domain.InvalidRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/**
 * 조사역 질문 답변 한 건 (ops.case_questions, V017 — 설계 문서 9.1, 계약서 5.3 A8·C9, SCRUM-302). 추가만 한다(UPDATE·DELETE 회수).
 * created_by 는 물은 사람(담당 조사역·승인자, 로그인 사용자 — CreatedOnlyEntity auditing). 요청 본문으로 받지 않는다.
 *
 * <p>상태별 칸 규칙(규약 5.4)은 {@link #record} 와 V017 CHECK 가 같다:
 * 답은 ANSWERED 만, 범위 밖 사유는 OUT_OF_SCOPE 만, 근거 거래는 ANSWERED 만(드물게 []).
 */
@Entity
@Table(name = "case_questions")
public class CaseQuestion extends CreatedOnlyEntity {

    /** 질문 글자 수 상한 — NFC 뒤 UTF-16 코드 단위 (AI·화면·STR 수정본과 같은 세기, 규약 5.4). */
    public static final int MAX_QUESTION_LENGTH = 300;
    /**
     * 원문 코드 포인트 상한 = V017 CHECK. NFC 300자를 통과한 원문은 정준 분해가 최대 4배라(예: U+1F82) 이 안에 든다 —
     * 그래도 DB CHECK 가 500 으로 새지 않게 여기서 한 번 더 400 으로 막는다.
     */
    static final int MAX_RAW_CODE_POINTS = 1_200;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Column(name = "question", nullable = false, updatable = false, columnDefinition = "text")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 16)
    private CaseQuestionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "out_of_scope_reason", updatable = false, length = 16)
    private QuestionOutOfScopeReason outOfScopeReason;

    @Column(name = "answer", updatable = false, columnDefinition = "text")
    private String answer;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "evidence_transaction_ids", nullable = false, updatable = false)
    private Long[] evidenceTransactionIds;

    @Column(name = "ai_generation_id", nullable = false, updatable = false, length = 64)
    private String aiGenerationId;

    @Column(name = "request_id", updatable = false, length = 64)
    private String requestId;

    /** JPA 용. */
    protected CaseQuestion() {}

    private CaseQuestion(Long caseId, String question, CaseQuestionStatus status, QuestionOutOfScopeReason reason,
                         String answer, List<Long> evidence, String aiGenerationId, String requestId) {
        this.caseId = caseId;
        this.question = question;
        this.status = status;
        this.outOfScopeReason = reason;
        this.answer = answer;
        this.evidenceTransactionIds = evidence.toArray(Long[]::new);
        this.aiGenerationId = aiGenerationId;
        this.requestId = requestId;
    }

    /**
     * AI 가 답한 질문 한 건을 기록한다. 응답 칸 확인(필수 칸·상태별 규칙·근거 거래 범위)은 AiClient.ask 가 먼저 했다 —
     * 여기서는 저장 규칙(V017 CHECK 와 같음)만 다시 지킨다.
     */
    public static CaseQuestion record(Long caseId, String question, CaseQuestionStatus status,
                                      QuestionOutOfScopeReason outOfScopeReason, String answer,
                                      List<Long> evidenceTransactionIds, String aiGenerationId, String requestId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(aiGenerationId, "aiGenerationId");
        requireValidQuestion(question);
        List<Long> evidence = evidenceTransactionIds == null ? List.of() : List.copyOf(evidenceTransactionIds);
        if ((status == CaseQuestionStatus.OUT_OF_SCOPE) != (outOfScopeReason != null)) {
            throw new IllegalArgumentException("범위 밖 사유는 OUT_OF_SCOPE 일 때만: " + status);
        }
        if ((status == CaseQuestionStatus.ANSWERED) != (answer != null)) {
            throw new IllegalArgumentException("답은 ANSWERED 일 때만: " + status);
        }
        if (status != CaseQuestionStatus.ANSWERED && !evidence.isEmpty()) {
            throw new IllegalArgumentException("근거 거래는 ANSWERED 일 때만: " + status);
        }
        return new CaseQuestion(caseId, question, status, outOfScopeReason, answer, evidence, aiGenerationId, requestId);
    }

    /**
     * 질문 입력 규칙 (규약 5.4, 계약서 5.3 A8) — 틀리면 400 INVALID_REQUEST details.question.
     * 앞뒤 공백은 자르지 않고 그대로 센다. 공백만이면 거절 — 공백 판정은 AI 서버(파이썬 str.isspace)와 같게 해야
     * 백엔드가 통과시킨 질문을 AI 가 400 으로 거절하는(→ 502) 일이 없다.
     */
    public static void requireValidQuestion(String question) {
        if (question == null || isBlankLikePython(question)) {
            throw new InvalidRequestException("question", "질문을 입력해 주세요");
        }
        if (Normalizer.normalize(question, Normalizer.Form.NFC).length() > MAX_QUESTION_LENGTH
                || question.codePointCount(0, question.length()) > MAX_RAW_CODE_POINTS) {
            throw new InvalidRequestException("question", "질문은 " + MAX_QUESTION_LENGTH + "자까지 쓸 수 있어요");
        }
    }

    /**
     * 모든 글자가 파이썬 {@code str.isspace()} 참인가 (빈 문자열 포함). 파이썬 isspace = 유니코드 공백 분류(Zs·Zl·Zp)
     * + 제어 문자 \t \n \u000b \f \r \u001c~\u001f \u0085. Java isWhitespace 는 NBSP 류(U+00A0·U+2007·U+202F)를 빼서 isSpaceChar 를 같이 본다.
     */
    static boolean isBlankLikePython(String s) {
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85);
    }

    public Long getCaseId() { return caseId; }
    public String getQuestion() { return question; }
    public CaseQuestionStatus getStatus() { return status; }
    public QuestionOutOfScopeReason getOutOfScopeReason() { return outOfScopeReason; }
    public String getAnswer() { return answer; }
    public List<Long> getEvidenceTransactionIds() {
        return evidenceTransactionIds == null ? List.of() : List.of(evidenceTransactionIds);
    }
    public String getAiGenerationId() { return aiGenerationId; }
    public String getRequestId() { return requestId; }
}
