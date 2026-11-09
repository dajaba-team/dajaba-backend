package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.LatestVerificationRepository;
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

    public GetStrDraft(StrDraftRepository drafts, CaseRepository cases, LatestVerificationRepository verifications,
                       CurrentUser currentUser, AccessLogWriter accessLog) {
        this.drafts = drafts;
        this.cases = cases;
        this.verifications = verifications;
        this.currentUser = currentUser;
        this.accessLog = accessLog;
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
        return Result.full(draft, c, verification);
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
            StrDraftStatus status,
            Long version,
            Long caseVersion,
            Instant producedAt,
            JsonNode facts,
            List<NarrativeSection> narrative,
            Integer currentRevisionNo,
            JsonNode latestVerification
    ) {

        static Result full(StrDraft draft, Case c, JsonNode latestVerification) {
            return new Result(DisplayNumber.draftNo(draft.getId()), Access.FULL, c.getCaseNo(), draft.getStatus(),
                    draft.getVersion(), c.getVersion(), draft.getProducedAt(), draft.getFacts(), draft.currentBody(),
                    draft.getCurrentRevision().getRevisionNo(), latestVerification);
        }

        static Result metaOnly(StrDraft draft) {
            return new Result(DisplayNumber.draftNo(draft.getId()), Access.META_ONLY, null, draft.getStatus(),
                    null, null, draft.getProducedAt(), null, null, null, null);
        }
    }
}
