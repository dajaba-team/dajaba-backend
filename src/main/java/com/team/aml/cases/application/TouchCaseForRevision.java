package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.security.CurrentUser;
import jakarta.persistence.OptimisticLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 수정본 저장 전에 케이스를 잠그고 확인한 뒤 version 만 1 올린다 (NFR-APR-02, 미결 20).
 * 수정본 저장은 초안 행만 바꾸고 케이스 행은 안 바꾼다. 그래도 케이스 version 을 올려 두면 같은 케이스에 동시에 들어온
 * 승인 요청·종결과 version 으로 부딪혀 한쪽이 409 CONCURRENT_UPDATE 가 된다. 상태·칸·이력은 그대로.
 *
 * <p>str 은 cases 의 담당자·상태를 볼 방법이 없으므로 잠근 뒤 여기서 확인한다(str.md "잠근 뒤 재확인"):
 * 케이스 잠금 조회(404) → version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 * → DRAFTED 이고 현재 초안이 draftId(409 INVALID_CASE_TRANSITION, details.action=REVISE_DRAFT).
 * 보완 반려(REJECTED+NEED_REVISION) 뒤 첫 수정은 이 유스케이스가 아니라 ReopenCaseDraft 몫 〔채영 확인 필요〕.
 *
 * <p>str 수정본 저장 트랜잭션 <b>맨 앞에서</b> 부른다(MANDATORY — 혼자 부르면 IllegalTransactionStateException).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TouchCaseForRevision {

    private final CaseRepository cases;
    private final CurrentUser currentUser;

    public TouchCaseForRevision(CaseRepository cases, CurrentUser currentUser) {
        this.cases = cases;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         수정하려는 초안 id. 케이스의 현재 초안이어야 한다
     * @param expectedVersion 화면·요청이 들고 온 cases.version(case_version). 다르면 409
     */
    public void execute(long caseId, long draftId, long expectedVersion) {
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
        cases.bumpVersion(c);
    }
}
