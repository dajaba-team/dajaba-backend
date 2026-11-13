package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.security.CurrentUser;
import jakarta.persistence.OptimisticLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 수정본 저장 전에 케이스를 잠그고 확인한 뒤 케이스 version 을 1 올린다 (NFR-APR-02, 미결 20).
 * 케이스 version 을 올려 두면 같은 케이스에 동시에 들어온 승인 요청·종결과 version 으로 부딪혀 한쪽이 409 CONCURRENT_UPDATE 가 된다.
 *
 * <p>str 은 cases 의 담당자·상태를 볼 방법이 없으므로, 수정본 저장 때는 상태와 상관없이 <b>이것 하나만</b> 부른다
 * (#43 리뷰 2번, 채영 확인 A안 — ReopenCaseDraft 를 따로 두지 않는다). 잠근 뒤 여기서 확인한다(str.md "잠근 뒤 재확인"):
 * 케이스 잠금 조회(404) → version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 * → 현재 초안이 있는데 draftId 가 아니면 409 DRAFT_SUPERSEDED(details.current_draft_no, SCRUM-223)
 * → DRAFTED 또는 보완 반려(REJECTED+NEED_REVISION)이고 현재 초안이 있음(409 INVALID_CASE_TRANSITION, details.action=REVISE_DRAFT·draft_no).
 *
 * <ul>
 *   <li>DRAFTED — version 만 +1. 상태·칸·이력은 그대로.</li>
 *   <li>REJECTED+NEED_REVISION — DRAFTED 로 되돌리고 반려 코드를 지우며 REOPEN_DRAFT 이력 한 행. version 은 상태 변경으로 +1
 *       (따로 한 번 더 올리지 않는다). 보완 반려 뒤 같은 본문 재요청 409 NARRATIVE_UNCHANGED 는 승인 요청 때 str ApprovalGate 몫.</li>
 *   <li>그 밖(PENDING_APPROVAL, REJECTED+NOT_REPORTABLE 등) — 409, 아무것도 바뀌지 않는다.</li>
 * </ul>
 *
 * <p>str 수정본 저장 트랜잭션 <b>맨 앞에서</b> 부른다(MANDATORY — 혼자 부르면 IllegalTransactionStateException).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TouchCaseForRevision {

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final CurrentUser currentUser;

    public TouchCaseForRevision(CaseRepository cases, CaseHistoryRepository histories, CurrentUser currentUser) {
        this.cases = cases;
        this.histories = histories;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         수정하려는 초안 id. 케이스의 현재 초안이어야 한다
     * @param expectedVersion 화면·요청이 들고 온 cases.version(case_version). 다르면 409
     * @return 새 cases.version — str 이 수정본 저장 응답의 case_version 에 그대로 싣는다 (SCRUM-141)
     */
    public long execute(long caseId, long draftId, long expectedVersion) {
        Case c = cases.findForUpdate(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        if (c.getVersion() != expectedVersion) {
            throw new OptimisticLockException("케이스가 그 사이 바뀌었습니다: " + c.getCaseNo()
                    + " (expected " + expectedVersion + ", current " + c.getVersion() + ")");
        }
        long actorId = currentUser.require().userId();
        if (c.getAssigneeId() == null || c.getAssigneeId() != actorId) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        c.requireRevisable(draftId);
        if (c.isRejectedForRevision()) {
            c.reopenDraft(actorId);                       // → DRAFTED, 반려 코드 지움, REOPEN_DRAFT 이력
            Case saved = cases.saveAndFlush(c);           // 상태가 바뀌었으니 flush 때 @Version 이 +1
            histories.saveAll(saved.takePendingHistories());
            return saved.getVersion();
        }
        cases.bumpVersion(c);                             // 상태는 그대로, version 만 +1 (바로 UPDATE)
        return c.getVersion();
    }
}
