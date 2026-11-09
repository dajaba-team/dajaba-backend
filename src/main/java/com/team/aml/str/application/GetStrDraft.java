
package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.NotAssigneeException;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GetStrDraft {

    private static final Pattern DRAFT_NO =
            Pattern.compile("^DRF-(\\d{6,})$");

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final LatestVerificationRepository verifications;
    private final CurrentUser currentUser;
    private final AccessLogWriter accessLog;

    public GetStrDraft(
            StrDraftRepository drafts,
            CaseRepository cases,
            LatestVerificationRepository verifications,
            CurrentUser currentUser,
            AccessLogWriter accessLog
    ) {
        this.drafts = drafts;
        this.cases = cases;
        this.verifications = verifications;
        this.currentUser = currentUser;
        this.accessLog = accessLog;
    }

    /**
     * 비담당 조사역의 403 시도도 access_logs에 남겨야 한다.
     * noRollbackFor로 감사 로그 INSERT를 커밋한다.
     */
    @Transactional(noRollbackFor = NotAssigneeException.class)
    public Result execute(String draftNo) {

        // 1. 로그인 사용자 확인
        AppUserDetails actor = currentUser.require();

        // 2. 화면용 초안 번호를 DB ID로 변환
        long draftId = parseDraftId(draftNo);

        // 3. 초안 조회
        StrDraft draft = drafts.findById(draftId)
                .orElseThrow(() ->
                        new DraftNotFoundException(draftNo)
                );

        // 4. 초안이 속한 케이스 조회
        Case investigationCase = cases.findById(draft.getCaseId())
                .orElseThrow(() ->
                        new CaseNotFoundException(draft.getCaseId())
                );

        // 5. 역할별 권한 검사
        Role role = Role.valueOf(actor.role());

        if (role == Role.INVESTIGATOR
                && !Objects.equals(
                investigationCase.getAssigneeId(),
                actor.userId()
        )) {

            // 403 접근 시도도 감사 로그에 기록
            accessLog.strView(draft.getId());

            throw new NotAssigneeException(
                    investigationCase.getCaseNo()
            );
        }

        if (role != Role.INVESTIGATOR
                && role != Role.APPROVER
                && role != Role.ADMIN) {
            throw new AccessDeniedException(
                    "STR 초안을 조회할 수 있는 역할이 아닙니다"
            );
        }

        // 6. 정상 조회 이력 기록
        accessLog.strView(draft.getId());

        // 7. ADMIN은 메타데이터만 반환
        if (role == Role.ADMIN) {
            return Result.metaOnly(draft);
        }

        // 8. 전체 조회 권한이면 최신 검증 결과 조회
        JsonNode latestVerification =
                verifications.findLatestPayloadByDraftId(draftId)
                        .orElse(null);

        return Result.full(
                draft,
                investigationCase.getCaseNo(),
                latestVerification
        );
    }

    /**
     * DRF-000123 → 123
     * 숫자 ID나 잘못된 형식은 외부에서 허용하지 않는다.
     */
    private static long parseDraftId(String draftNo) {

        Matcher matcher = draftNo == null
                ? null
                : DRAFT_NO.matcher(draftNo);

        if (matcher == null || !matcher.matches()) {
            throw new DraftNotFoundException(
                    String.valueOf(draftNo)
            );
        }

        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new DraftNotFoundException(draftNo);
        }
    }

    /**
     * Application 결과.
     * API DTO에 의존하지 않고 조회 결과만 전달한다.
     */
    public record Result(
            String draftId,
            String generationId,
            String caseNo,
            StrDraftStatus status,
            Long version,
            String generatorVersion,
            Instant producedAt,
            JsonNode facts,
            List<NarrativeSection> narrative,
            Integer currentRevisionNo,
            JsonNode latestVerification,
            boolean metaOnly
    ) {

        static Result full(
                StrDraft draft,
                String caseNo,
                JsonNode latestVerification
        ) {
            return new Result(
                    DisplayNumber.draftNo(draft.getId()),
                    draft.getGenerationId(),
                    caseNo,
                    draft.getStatus(),
                    draft.getVersion(),
                    draft.getGeneratorVersion(),
                    draft.getProducedAt(),
                    draft.getFacts(),
                    draft.currentBody(),
                    draft.getCurrentRevision().getRevisionNo(),
                    latestVerification,
                    false
            );
        }

        static Result metaOnly(StrDraft draft) {
            return new Result(
                    DisplayNumber.draftNo(draft.getId()),
                    null,
                    null,
                    draft.getStatus(),
                    null,
                    null,
                    draft.getProducedAt(),
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }
    }
}
