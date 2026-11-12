package com.team.aml.str.application;

import com.team.aml.cases.application.ListCasesPendingApproval;
import com.team.aml.cases.application.PendingCase;
import com.team.aml.cases.application.WasCaseAssignee;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.VerificationResultRepository;
import com.team.aml.user.domain.Role;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 승인함 목록 (계약서 P1). 승인자·관리자(조회만) — 역할은 SecurityConfig 가 GET /approvals/** 로 먼저 막는다.
 *
 * <p>조합 (08_decisions 10/30 "str 조회 유스케이스가 조합, REPEATABLE READ"):
 * <ol>
 *   <li>케이스 줄 — cases 공개 조회 {@link ListCasesPendingApproval} (#101). 승인 대기만, 요청 오래된 순·페이지까지 cases 가 정한다</li>
 *   <li>줄마다 현재 초안의 최신 검증(str verification_results)·최신 반려(str approvals)를 붙인다</li>
 *   <li>줄마다 "이 사람이 승인·반려할 수 있나" — cases 공개 조회 {@link WasCaseAssignee} 하나로 (승인·반려 유스케이스와 같은 판단, 미결 32)</li>
 * </ol>
 * REPEATABLE READ 라 1~3이 같은 스냅샷을 읽는다 — 목록을 읽는 사이 다른 요청이 승인·반려해도 한 줄 안의 값이 서로 다른 시점이 되지 않는다.
 * 본문이 없는 목록이라 열람 기록(strView)을 남기지 않는다 — 그래서 readOnly 를 쓸 수 있다(08_decisions 11/3).
 */
@Service
public class ListPendingApprovals {

    static final List<String> APPROVER_ACTIONS = List.of("APPROVE", "REJECT");   // 계약서 S2 available_actions 이름과 같음

    private final ListCasesPendingApproval listCases;
    private final WasCaseAssignee wasCaseAssignee;
    private final VerificationResultRepository verifications;
    private final ApprovalRepository approvals;
    private final CurrentUser currentUser;

    public ListPendingApprovals(ListCasesPendingApproval listCases, WasCaseAssignee wasCaseAssignee,
                                VerificationResultRepository verifications, ApprovalRepository approvals,
                                CurrentUser currentUser) {
        this.listCases = listCases;
        this.wasCaseAssignee = wasCaseAssignee;
        this.verifications = verifications;
        this.approvals = approvals;
        this.currentUser = currentUser;
    }

    /**
     * @param page 0부터
     * @param size 1~100 — 형식 검사(400)는 컨트롤러(ApprovalListQuery)가 먼저 한다
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Item> execute(int page, int size) {
        AppUserDetails me = currentUser.require();
        boolean approver = Role.valueOf(me.role()) == Role.APPROVER;
        return listCases.execute(page, size).map(c -> item(c, approver, me.userId()));
    }

    private Item item(PendingCase c, boolean approver, long myId) {
        long draftId = c.currentDraftId();

        Item.Verification verification = verifications.findLatest(draftId)
                .map(v -> new Item.Verification(v.getVerificationId(), v.getRevisionNo(), v.getStatus().name(),
                        v.isPassed()))
                .orElse(null);
        Item.Rejection lastRejection = approvals.findLatestRejection(draftId)
                .map(a -> new Item.Rejection(a.getReasonCode().name(), a.getComment(), a.getCreatedAt()))
                .orElse(null);

        boolean wasAssignee = approver && wasCaseAssignee.execute(c.caseId(), myId);
        List<String> actions = approver && !wasAssignee ? APPROVER_ACTIONS : List.of();   // 관리자는 목록만, 버튼 없음

        Item.Assignee assignee = c.assignee() == null ? null
                : new Item.Assignee(c.assignee().loginId(), c.assignee().displayName());
        return new Item(draftId, c.caseNo(), c.version(), assignee, c.submittedAt(),
                verification, lastRejection, wasAssignee, actions);
    }

    /**
     * 승인함 한 줄. cases 클래스(PendingCase·UserRef)를 그대로 내보내지 않고 str 값으로 옮긴다 —
     * str api 는 cases 를 볼 수 없다(ArchUnit "api 는 다른 기능에 의존하지 않는다").
     *
     * @param caseVersion      지금 cases.version — 승인·반려 요청의 case_version 으로 쓴다
     * @param submittedAt      마지막 승인 요청 시각 (DB 값 그대로 — 초 자르기는 api)
     * @param verification     현재 초안의 최신 검증 (VerificationResultRepository.findLatest — 늦게 저장된 검증 예외는 그 주석). 없으면 null
     * @param lastRejection    <b>현재 초안</b>의 최신 반려. 처음 요청이면 null. 재생성으로 새 초안이 되면 예전 초안의 반려는 안 본다
     *                         — S2 last_rejection·NARRATIVE_UNCHANGED 판단과 같은 초안 기준 (#103 리뷰 2번, str.md P1)
     * @param wasAssignee      내가 지금·예전에 담당한 케이스 — 화면은 "직접 담당했던 케이스"로 표시 (W4 계획 채영 11/11)
     * @param availableActions 승인자이고 담당한 적 없으면 [APPROVE, REJECT], 그 밖(관리자·예전 담당)은 []
     */
    public record Item(long draftId, String caseNo, long caseVersion, Assignee assignee, Instant submittedAt,
                       Verification verification, Rejection lastRejection,
                       boolean wasAssignee, List<String> availableActions) {

        public record Assignee(String loginId, String displayName) {}

        public record Verification(String verificationId, int revisionNo, String status, boolean passed) {}

        /** reasonCode 는 NEED_REVISION·NOT_REPORTABLE. decidedAt = approvals.created_at. */
        public record Rejection(String reasonCode, String comment, Instant decidedAt) {}
    }
}
