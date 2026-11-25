package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.application.WasCaseAssignee;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.LatestVerificationRepository;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftStatus;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STR 초안 상세 조회 (계약서 S2).
 * 본문(FULL)은 담당 조사역·승인자만. 비담당 조사역·ADMIN 은 403 이 아니라 제한 응답(META_ONLY).
 * 열람 기록 STR_VIEW 는 FULL·META_ONLY 모두 한 줄 — 권한 판단 뒤, 본문 읽기 전. 404 는 기록하지 않는다.
 */
@Service
public class GetStrDraft {

    private static final Pattern DRAFT_NO = Pattern.compile("DRF-(\\d{6,})");

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final LatestVerificationRepository verifications;
    private final CurrentUser currentUser;
    private final AccessLogWriter accessLog;
    private final CurrentNarrative currentNarrative;
    private final ApprovalRepository approvals;
    private final UserRepository users;
    private final WasCaseAssignee wasCaseAssignee;
    private final StrReportRepository reports;
    private final Duration staleAfter;
    private final AmlProperties properties;

    public GetStrDraft(StrDraftRepository drafts, CaseRepository cases, LatestVerificationRepository verifications,
                       CurrentUser currentUser, AccessLogWriter accessLog, CurrentNarrative currentNarrative,
                       ApprovalRepository approvals, UserRepository users, WasCaseAssignee wasCaseAssignee,
                       StrReportRepository reports,
                       @Value(StrReportPdfAccess.STALE_PENDING_AFTER) Duration staleAfter, AmlProperties properties) {
        this.drafts = drafts;
        this.cases = cases;
        this.verifications = verifications;
        this.currentUser = currentUser;
        this.accessLog = accessLog;
        this.currentNarrative = currentNarrative;
        this.approvals = approvals;
        this.users = users;
        this.wasCaseAssignee = wasCaseAssignee;
        this.reports = reports;
        this.staleAfter = staleAfter;
        this.properties = properties;
    }

    /** readOnly 아님 — accessLog.strView 가 MANDATORY·쓰기 트랜잭션을 요구한다. */
    @Transactional
    public Result execute(String draftNo) {
        AppUserDetails me = currentUser.require();
        long draftId = parseDraftNo(draftNo).orElseThrow(() -> new DraftNotFoundException(draftNo));
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        accessLog.strView(draftId);
        Role role = Role.valueOf(me.role());
        if (!canReadBody(role, c, me.userId())) return Result.metaOnly(draft, c);
        JsonNode verification = verifications.findLatestByDraftId(draftId).orElse(null);
        LastRejection lastRejection = approvals.findLatestRejection(draftId)
                .map(a -> lastRejection(draft, a))
                .orElse(null);
        // 예전 담당 조회는 버튼이 걸린 경우(승인자 + 승인 대기)에만 — 그 밖엔 결과가 같아 쿼리를 아낀다
        boolean wasAssignee = role == Role.APPROVER && c.getStatus() == CaseStatus.PENDING_APPROVAL
                && wasCaseAssignee.execute(c.getId(), me.userId());
        // 확정 보고서 조회는 버튼이 걸린 경우(승인자 + 승인됨·보고됨 + 잠긴 초안)에만 — 승인한 사람인지는 읽어 봐야 안다.
        // APPROVED 는 REPORT(승인한 사람), REPORTED 는 REGENERATE_PDF(승인한 사람 + 다시 만들 수 있는 상태)에만 쓴다
        Optional<StrReport> report = role == Role.APPROVER
                && (c.getStatus() == CaseStatus.APPROVED || c.getStatus() == CaseStatus.REPORTED)
                && draft.getStatus() == StrDraftStatus.LOCKED
                ? reports.findByDraftId(draftId) : Optional.empty();
        boolean approvedByMe = c.getStatus() == CaseStatus.APPROVED
                && report.map(r -> r.isApprovedBy(me.userId())).orElse(false);
        boolean canRegenerate = c.getStatus() == CaseStatus.REPORTED && report
                .map(r -> StrReportPdfAccess.canRegenerate(role, r, me.userId(), Instant.now(), staleAfter)).orElse(false);
        List<String> actions = new ArrayList<>(StrDraftActions.of(draftId, draft.getStatus(), c, me.userId(), role,
                wasAssignee, approvedByMe, canRegenerate));
        // 참고 사례 찾기(S9) — 초안 상태와 상관없이, 본문을 볼 수 있고(여기까지 오면 FULL) 설정이 켜져 있으면 (설계 문서 A 7.1)
        if (properties.ai().referenceSearchEnabled()) {
            actions.add(StrDraftActions.SEARCH_REFERENCES);
        }
        return Result.full(draft, c, verification, currentNarrative.of(draft), lastRejection, List.copyOf(actions));
    }

    /**
     * 이 초안의 가장 최근 반려 (계약서 S2 last_rejection — 편집 화면 맨 위 "보완 요청: …" 한 줄).
     * revision_no = 반려 당시 수정본 번호, decided_by = 승인자 표시 이름(S4 edited_by 와 같은 모양 — users.id 는 안 내보냄),
     * narrative_unchanged = 지금 본문 해시가 반려 당시와 같은가 — 같으면 다시 요청해도 409 NARRATIVE_UNCHANGED (ApprovalGate 4번과 같은 기준).
     */
    private LastRejection lastRejection(StrDraft draft, Approval a) {
        Integer revisionNo = draft.getRevisions().stream()
                .filter(r -> Objects.equals(r.getId(), a.getRevisionId()))
                .map(r -> r.getRevisionNo())
                .findFirst().orElse(null);
        String decidedBy = a.getCreatedBy() == null ? null
                : users.findById(a.getCreatedBy()).map(User::getDisplayName).orElse(null);
        boolean unchanged = a.getNarrativeSha256().equals(NarrativeHash.of(draft.currentBody()));
        return new LastRejection(a.getReasonCode().name(), a.getComment(), revisionNo, decidedBy, a.getCreatedAt(),
                unchanged);
    }

    /** S2 last_rejection. reasonCode 는 NEED_REVISION·NOT_REPORTABLE 문자열 — str api 는 cases enum 을 못 본다(ArchUnit). */
    public record LastRejection(String reasonCode, String comment, Integer revisionNo, String decidedBy,
                                Instant decidedAt, boolean narrativeUnchanged) {}

    /**
     * 화면에 주는 참고 사례 칸 — AI Reference(shared/schemas/str/draft.py) 중 text 를 뺀 것.
     * text 는 AI 가 프롬프트에 쓰는 원문 조각(최대 600자)이라 화면에 내보내지 않는다(계약서 S2 references).
     * excerpt 는 화면에서 마우스를 올리면 보이는 "이 자료의 관련 구절"로, AI 가 고른 최대 300 UTF-16 코드 유닛이다. 예전 초안에서는 null (SCRUM-426).
     * <ul>
     *   <li>page 는 <b>PDF 쪽 번호(1부터)</b>다 — 문서에 인쇄된 쪽 번호와 다를 수 있다(예: PDF 43~44쪽 = 인쇄 41~42쪽).
     *       화면은 "PDF n쪽"처럼 기준을 같이 적어 헷갈리지 않게 한다 (#117 리뷰)</li>
     *   <li>건수는 AI 프롬프트가 정한다 — narrative_v2(기본) 최대 3건, v3 최대 2건. 계약서가 "0~3건"이라 여기서 자르지 않는다</li>
     * </ul>
     */
    static final List<String> REFERENCE_FIELDS = List.of("chunk_id", "doc_id", "doc_title", "page", "score", "excerpt");

    /** 저장된 AI 응답(payload)의 generation_meta.references → 화면 칸만 골라 새 객체로. 배열이 아니거나 없으면 []. */
    static List<JsonNode> references(JsonNode payload) {
        JsonNode refs = payload == null ? null : payload.path("generation_meta").path("references");
        if (refs == null || !refs.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode ref : refs) {
            if (!ref.isObject()) continue;
            ObjectNode picked = JsonNodeFactory.instance.objectNode();
            for (String field : REFERENCE_FIELDS) {
                picked.set(field, ref.has(field) ? ref.get(field).deepCopy() : NullNode.getInstance());   // 없는 칸도 키는 null 로
            }
            out.add(picked);
        }
        return List.copyOf(out);
    }

    /** 본문은 승인자 또는 담당 조사역만 — 참고 사례 찾기(SearchReferences)도 같은 판단. */
    static boolean canReadBody(Role role, Case c, long userId) {
        return role == Role.APPROVER || (role == Role.INVESTIGATOR && Objects.equals(c.getAssigneeId(), userId));
    }

    /** DRF-000123 → 123. 형식이 틀리거나 long 범위를 넘으면 비어 있다(404). DisplayNumber.draftNo 의 반대. */
    private static Optional<Long> parseDraftNo(String draftNo) {
        Matcher m = draftNo == null ? null : DRAFT_NO.matcher(draftNo);
        if (m == null || !m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(m.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public enum Access { FULL, META_ONLY }

    /**
     * 조회 결과. META_ONLY 면 draftNo·access·caseNo·caseStatus·status·producedAt 만 채우고 나머지는 null
     * (계약서 S2 제한 응답 — "상태·날짜만". 케이스 번호·상태는 C1 목록에서 세 역할 모두 보는 값)
     *
     * @param suspicionTypes            조사역이 승인 요청(S6) 때 고른 의심 유형. 요청 전이면 [] — 반려 뒤 다시 열면 지난 값
     * @param suspicionTypesRecommended 룰이 추천한 유형. 룰 → 서식 VI 유형 표가 아직 없어 지금은 항상 [] (rules.md 미결 4)
     * @param suspicionLevelBranch      지점용 의심 정도 1~5 (S6). 요청 전이면 null
     * @param suspicionLevelHq          본점용 의심 정도 1~5 (P2). 승인 전이면 null
     * @param references                참고 사례(RAG) 0~3건 — 저장된 AI 응답의 generation_meta.references 에서 옮긴다(SCRUM-125).
     *                                  AI 가 안 줬으면 []. 칸은 {@link #REFERENCE_FIELDS} 만 (text 는 프롬프트용이라 뺀다)
     * @param lastRejection             이 초안의 가장 최근 반려. 반려된 적 없으면 null
     * @param availableActions          STR 화면 버튼 (StrDraftActions). META_ONLY 는 [] — "할 수 있는 것 없음"
     */
    public record Result(
            String draftNo,
            Access access,
            String caseNo,
            String caseStatus,
            StrDraftStatus status,
            Long version,
            Long caseVersion,
            Instant producedAt,
            JsonNode facts,
            List<NarrativeSection> narrative,
            Integer currentRevisionNo,
            JsonNode latestVerification,
            String generationId,
            String generatorVersion,
            List<String> suspicionTypes,
            List<String> suspicionTypesRecommended,
            Integer suspicionLevelBranch,
            Integer suspicionLevelHq,
            List<JsonNode> references,
            LastRejection lastRejection,
            List<String> availableActions
    ) {

        /** narrative = CurrentNarrative — 문장은 지금 수정본, 근거는 같은 해시 최신 검증의 narrative_refs 우선. */
        static Result full(StrDraft draft, Case c, JsonNode latestVerification, List<NarrativeSection> narrative,
                           LastRejection lastRejection, List<String> availableActions) {
            int currentRevisionNo = draft.getCurrentRevision().getRevisionNo();
            return new Result(DisplayNumber.draftNo(draft.getId()), Access.FULL, c.getCaseNo(), c.getStatus().name(),
                    draft.getStatus(), draft.getVersion(), c.getVersion(), draft.getProducedAt(), draft.getFacts(),
                    narrative, currentRevisionNo, withIsCurrent(latestVerification, NarrativeHash.of(draft.currentBody())),
                    draft.getGenerationId(), draft.getGeneratorVersion(),
                    draft.getSuspicionTypes(), List.of(), draft.getSuspicionLevelBranch(), draft.getSuspicionLevelHq(),
                    GetStrDraft.references(draft.getPayload()), lastRejection, availableActions);
        }

        /**
         * is_current = 이 검증이 지금 본문(같은 NarrativeHash)을 검사했는가. 수정본 번호가 아니라 해시로 본다 —
         * 고쳤다가 되돌려 문장이 같으면 true, 근거(cites·evidence_ids)만 바뀌어도 true(해시에 안 들어감).
         * false 면 화면은 [승인 요청]을 끄고 "다시 검증"을 띄운다 (S6 버튼, #84 리뷰). ApprovalGate 와 같은 기준.
         */
        private static JsonNode withIsCurrent(JsonNode verification, String currentHash) {
            if (!(verification instanceof ObjectNode v)) {
                return verification;
            }
            v.put("is_current", currentHash.equals(v.path("narrative_sha256").asText(null)));
            return v;
        }

        static Result metaOnly(StrDraft draft, Case c) {
            return new Result(DisplayNumber.draftNo(draft.getId()), Access.META_ONLY, c.getCaseNo(), c.getStatus().name(),
                    draft.getStatus(), null, null, draft.getProducedAt(), null, null, null, null,
                    null, null, null, null, null, null, null, null, List.of());
        }
    }
}
