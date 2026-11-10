package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.LatestVerificationRepository;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftStatus;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    public GetStrDraft(StrDraftRepository drafts, CaseRepository cases, LatestVerificationRepository verifications,
                       CurrentUser currentUser, AccessLogWriter accessLog, CurrentNarrative currentNarrative) {
        this.drafts = drafts;
        this.cases = cases;
        this.verifications = verifications;
        this.currentUser = currentUser;
        this.accessLog = accessLog;
        this.currentNarrative = currentNarrative;
    }

    /** readOnly 아님 — accessLog.strView 가 MANDATORY·쓰기 트랜잭션을 요구한다. */
    @Transactional
    public Result execute(String draftNo) {
        AppUserDetails me = currentUser.require();
        long draftId = parseDraftNo(draftNo).orElseThrow(() -> new DraftNotFoundException(draftNo));
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        accessLog.strView(draftId);
        if (!canReadBody(Role.valueOf(me.role()), c, me.userId())) return Result.metaOnly(draft);
        JsonNode verification = verifications.findLatestByDraftId(draftId).orElse(null);
        return Result.full(draft, c, verification, currentNarrative.of(draft));
    }

    /** 본문은 승인자 또는 담당 조사역만. */
    private static boolean canReadBody(Role role, Case c, long userId) {
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

    /** 조회 결과. META_ONLY 면 draftNo·access·status·producedAt 만 채우고 나머지는 null. */
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
            JsonNode latestVerification
    ) {

        /** narrative = CurrentNarrative — 문장은 지금 수정본, 근거는 같은 해시 최신 검증의 narrative_refs 우선. */
        static Result full(StrDraft draft, Case c, JsonNode latestVerification, List<NarrativeSection> narrative) {
            int currentRevisionNo = draft.getCurrentRevision().getRevisionNo();
            return new Result(DisplayNumber.draftNo(draft.getId()), Access.FULL, c.getCaseNo(), c.getStatus().name(),
                    draft.getStatus(), draft.getVersion(), c.getVersion(), draft.getProducedAt(), draft.getFacts(),
                    narrative, currentRevisionNo, withIsCurrent(latestVerification, NarrativeHash.of(draft.currentBody())));
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

        static Result metaOnly(StrDraft draft) {
            return new Result(DisplayNumber.draftNo(draft.getId()), Access.META_ONLY, null, null, draft.getStatus(),
                    null, null, draft.getProducedAt(), null, null, null, null);
        }
    }
}
