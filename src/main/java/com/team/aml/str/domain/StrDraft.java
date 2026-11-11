package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.domain.AssignedIdEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * STR 초안. 상태는 메서드로만 바뀐다(setter 없음).
 * id = draft_id — AI 호출 전에 ops.str_draft_id_seq 로 먼저 받은 번호(AssignedIdEntity). 낙관적 락은 @Version.
 * 수정본은 revisions 에 추가만 한다. 지금 본문 = 마지막 수정본(revision_no 가 가장 큰 것)의 "고친 후".
 * */
@Entity
@Table(name = "str_drafts")
public class StrDraft extends AssignedIdEntity {

    private static final int MAX_SECTION_LENGTH = 5_000;
    private static final int MAX_SUSPICION_TYPE_LENGTH = 16;   // V008 suspicion_types varchar(16)[]
    private static final int MIN_SUSPICION_LEVEL = 1;          // V008 CHECK between 1 and 5
    private static final int MAX_SUSPICION_LEVEL = 5;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;                    // 케이스는 객체가 아니라 번호로만

    @Column(name = "generation_id", nullable = false, updatable = false, length = 64)
    private String generationId;            // AI 생성 번호 (23번)

    @Column(name = "generator_version", nullable = false, updatable = false, length = 64)
    private String generatorVersion;              // = generation_meta.prompt_version (11/3)

    @Column(name = "produced_at", nullable = false, updatable = false)
    private Instant producedAt;                   // AI가 준 생성 시각 (UTC Z)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private JsonNode payload;                     // AI 응답 전체

    /** payload -> 'facts' 를 DB가 만든다(generated column). 넣지도 고치지도 않는다 — 428C9 방지 (#28 리뷰). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facts", insertable = false, updatable = false)
    private JsonNode facts;                 // 숫자. 아무도 못 고친다

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StrDraftStatus status;

    /** 수정본 전부. 초안이 소유하고 str_revisions.draft_id 를 채운다. 추가만 한다. */
    @OneToMany(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @JoinColumn(name = "draft_id", nullable = false, updatable = false)
    @OrderBy("revisionNo ASC")
    private List<StrRevision> revisions = new ArrayList<>();

    /**
     * DB 의 현재 수정본 포인터(복합 FK). 수정본 id 는 INSERT 뒤에 나오므로 저장 직후 리포지토리가 맞춘다(syncCurrentRevisionId).
     * 도메인 판단에는 쓰지 않는다 — 지금 본문은 revisions 로 계산.
     * updatable = false: 변경 감지 UPDATE 로 내보내면 @PreUpdate auditing 이 updated_at 을 바꿔 버전이 한 번 더 오른다
     * (@OptimisticLock(excluded) 로는 못 막는다). 그래서 리포지토리가 버전·auditing 을 거치지 않는 UPDATE 로 따로 쓴다.
     */
    @Column(name = "current_revision_id", updatable = false)
    private Long currentRevisionId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "suspicion_types", nullable = false)
    private String[] suspicionTypes = new String[0];   // 서식 VI 유형 번호. 승인 요청 때 정한다 (W4)

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "suspicion_level_branch")
    private Integer suspicionLevelBranch;             // 지점용 1~5, 조사역 (W4)

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "suspicion_level_hq")
    private Integer suspicionLevelHq;                 // 본점용 1~5, 승인자 (W4)

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** JPA 용. */
    protected StrDraft() {}

    private StrDraft(Long draftId, Long caseId, String generationId, String generatorVersion,
                     Instant producedAt, JsonNode payload, List<NarrativeSection> aiNarrative) {
        super(draftId);
        this.caseId = caseId;
        this.generationId = generationId;
        this.generatorVersion = generatorVersion;
        this.producedAt = producedAt;
        this.payload = payload.deepCopy();
        this.facts = payload.get("facts").deepCopy();     // DB가 만들 값과 같은 것. 저장 전에도 getFacts() 가 되게
        this.status = StrDraftStatus.ACTIVE;
        this.revisions.add(StrRevision.aiOriginal(aiNarrative)); // 수정본 1번 = AI 원문
    }

    /**
     * AI 응답으로 초안을 만든다. draftId 는 AI 를 부르기 전에 받은 번호, payload 는 AI 응답 전체(facts 포함).
     */
    public static StrDraft create(Long draftId, Long caseId, String generationId, String generatorVersion,
                                  Instant producedAt, JsonNode payload, List<NarrativeSection> aiNarrative) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(producedAt, "producedAt");
        if (generationId == null || generationId.isBlank()) {   // VerificationResult.of()와 같은 규칙
            throw new IllegalArgumentException("generationId가 비어 있다");
        }
        if (generatorVersion == null || generatorVersion.isBlank()) {
            throw new IllegalArgumentException("generatorVersion이 비어 있다");
        }
        if (payload == null || !payload.path("facts").isObject()) {
            throw new IllegalArgumentException("payload에 facts 객체가 없다");
        }
        NarrativeSections.requireOneSet(aiNarrative, "AI 원문 단락");   // ← 만들기 전에 먼저 검사 (V1 6섹션 또는 V2 7항목 한 세트)
        aiNarrative.forEach(n -> validateText(n.section(), n.text()));   // 고친 문장과 같은 규칙
        return new StrDraft(draftId, caseId, generationId, generatorVersion, producedAt, payload,
                NarrativeSections.inSectionOrder(aiNarrative));
    }

    /**
     * 본문 수정. 고친 단락(단락 이름 → 새 문장)과 수정자를 받아 새 수정본을 만든다.
     * facts는 인자에 없다 — 숫자는 못 고친다.
     */
    public StrRevision revise(Map<StrSection, String> editedTexts, Long revisedBy) {
        requireActive();
        Objects.requireNonNull(revisedBy, "revisedBy");   // 세션에서 오는 값이라 null이면 호출 쪽 버그
        validateTexts(editedTexts);
        requireSectionsOfThisDraft(editedTexts);

        StrRevision current = getCurrentRevision();     // 처음 고치면 2번
        List<NarrativeSection> before = current.getAfter();
        List<NarrativeSection> after = applyEdits(before, editedTexts);

        StrRevision revision = new StrRevision(
                current.nextNo(), before, after,
                editedTexts.keySet().stream().sorted().toList(),
                revisedBy);

        revisions.add(revision);
        return revision;
    }

    /** 이 초안 본문에 있는 단락만 고칠 수 있다 — 6섹션 초안에 7항목 이름(또는 그 반대)으로 고치면 거부. 조용히 버리지 않는다. */
    private void requireSectionsOfThisDraft(Map<StrSection, String> editedTexts) {
        List<StrSection> own = currentBody().stream().map(NarrativeSection::section).toList();
        editedTexts.keySet().stream()
                .filter(section -> !own.contains(section))
                .findFirst()
                .ifPresent(section -> {
                    throw new InvalidSectionTextException(section, "이 초안에 없는 단락입니다");
                });
    }

    /** 고친 문장마다: 비었거나(공백만 포함) 5,000자를 넘으면 거부. */
    private static void validateTexts(Map<StrSection, String> editedTexts) {
        editedTexts.forEach((section, text) -> {
            if (section == null) throw new InvalidSectionTextException(null, "단락 이름이 없습니다");
            validateText(section, text);
        });
    }

    private static void validateText(StrSection section, String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidSectionTextException(section, "문장이 비어 있습니다");
        }
        if (lengthAfterNfc(text) > MAX_SECTION_LENGTH) {
            throw new InvalidSectionTextException(section, MAX_SECTION_LENGTH + "자를 넘습니다");
        }
    }
    /**
     * 글자 수 = NFC로 맞춘 뒤 UTF-16 길이(String.length()). AI 규약과 같은 기준이다 (11/1 공지, 06_verification).
     * 자소가 나뉜 한글(NFD)도 완성형 글자 수로 세고, 이모지는 2로 센다. 저장하는 문장은 그대로 두고 세기만 한다(해시는 NarrativeHash가 NFC로 맞춘다).
     */
    static int lengthAfterNfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).length();
    }

    /**
     * 승인 요청(S6) 때 조사역이 고른 의심 유형(서식 VI)·지점용 의심 정도. 편집 중 저장 API 는 없고 이때만 쓴다 (계약서 S6, 11/4).
     * 유형 1개 이상(중복 없이, 코드 16자 이하 — V008 varchar(16)), 정도 1~5. api 가 400 으로 먼저 막고 여기는 안전장치.
     * 반려 뒤 다시 열면 지난 값이 S2 로 채워져 온다 — 지우지 않는다.
     */
    public void recordBranchSuspicion(List<String> suspicionTypes, int levelBranch) {
        requireActive();
        if (suspicionTypes == null || suspicionTypes.isEmpty()) {
            throw new IllegalArgumentException("의심 유형은 1개 이상이어야 한다");
        }
        for (String type : suspicionTypes) {
            if (type == null || type.isBlank() || type.length() > MAX_SUSPICION_TYPE_LENGTH) {
                throw new IllegalArgumentException("의심 유형 코드는 1~" + MAX_SUSPICION_TYPE_LENGTH + "자여야 한다");
            }
        }
        if (suspicionTypes.stream().distinct().count() != suspicionTypes.size()) {
            throw new IllegalArgumentException("의심 유형이 중복됐다");
        }
        requireLevel(levelBranch);
        this.suspicionTypes = suspicionTypes.toArray(String[]::new);
        this.suspicionLevelBranch = levelBranch;
    }

    /** 승인(P2) 때 승인자가 고른 본점용 의심 정도 1~5. lock() 보다 먼저 부른다 (계약서 P2). */
    public void recordHqSuspicion(int levelHq) {
        requireActive();
        requireLevel(levelHq);
        this.suspicionLevelHq = levelHq;
    }

    private static void requireLevel(int level) {
        if (level < MIN_SUSPICION_LEVEL || level > MAX_SUSPICION_LEVEL) {
            throw new IllegalArgumentException("의심 정도는 " + MIN_SUSPICION_LEVEL + "~" + MAX_SUSPICION_LEVEL + "이어야 한다: " + level);
        }
    }

    /** 승인될 때 부른다. 되돌리는 메서드는 없다 */
    public void lock() {
        requireActive();
        this.status = StrDraftStatus.LOCKED;
    }

    /**
     * 다른 초안으로 대체될 때 부른다.
     * 같은 케이스에서 초안을 다시 만들었거나, 초안이 있는 케이스가 종료될 때.
     */
    public void supersede() {
        requireActive();
        this.status = StrDraftStatus.SUPERSEDED;
    }

    /** 금지 전이 상태 방지 , 사용 중이 아니면 거부 (잠김·대체됨) */
    public void requireActive(){
        if (status != StrDraftStatus.ACTIVE){
            throw new DraftLockedException(status);
        }
    }

    /**
     * 저장 직후 리포지토리만 부른다: 메모리의 current_revision_id 를 마지막 수정본 id 로 맞추고 그 id 를 돌려준다.
     * DB 반영은 리포지토리가 한다(컬럼이 updatable = false). 수정본이 아직 INSERT 되지 않았으면(id 없음) 호출 순서 버그다.
     */
    public Long syncCurrentRevisionId() {
        Long id = getCurrentRevision().getId();
        if (id == null) {
            throw new IllegalStateException("수정본이 아직 저장되지 않았다 — flush 뒤에 불러야 한다");
        }
        this.currentRevisionId = id;
        return id;
    }

    /** 지금 본문: 현재 수정본의 "고친 후". 안 고쳤으면 수정본 1번(AI 원문). */
    public List<NarrativeSection> currentBody() {
        return getCurrentRevision().getAfter();
    }

    /** 현재 수정본 = revision_no 가 가장 큰 것. */
    public StrRevision getCurrentRevision() {
        return revisions.get(revisions.size() - 1);
    }

    /** 수정본 전부, 번호 순. 밖에서 추가·삭제할 수 없다. */
    public List<StrRevision> getRevisions() { return List.copyOf(revisions); }

    public Long getCaseId() { return caseId; }
    public String getGenerationId() { return generationId; }
    public String getGeneratorVersion() { return generatorVersion; }
    public Instant getProducedAt() { return producedAt; }
    public StrDraftStatus getStatus() { return status; }
    public Long getCurrentRevisionId() { return currentRevisionId; }
    public List<String> getSuspicionTypes() { return List.of(suspicionTypes); }
    public Integer getSuspicionLevelBranch() { return suspicionLevelBranch; }
    public Integer getSuspicionLevelHq() { return suspicionLevelHq; }
    public long getVersion() { return version; }

    /** 사실값. 복사본을 돌려준다 — 밖에서 바꿔도 초안의 숫자는 그대로. */
    public JsonNode getFacts() { return facts.deepCopy(); }

    /** AI 응답 전체. 복사본. */
    public JsonNode getPayload() { return payload.deepCopy(); }

    /** before를 복사하되, 고친 단락은 문장(text)만 새 것으로 바꾼다. 근거(cites·evidence_ids)는 그대로. */
    private static List<NarrativeSection> applyEdits(List<NarrativeSection> before,
                                                     Map<StrSection, String> editedTexts) {
        return before.stream()
                .map(s -> editedTexts.containsKey(s.section())
                        ? s.withText(editedTexts.get(s.section()))
                        : s)
                .toList();
    }


}
