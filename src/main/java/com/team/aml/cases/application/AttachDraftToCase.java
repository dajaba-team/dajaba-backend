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
 * str 에 주는 공개 유스케이스 — 저장한 초안을 케이스에 연결하고 DRAFTED 로 (CASES 명세 §6.2, flows/03_str ⑩).
 * str SaveStrDraft 가 초안 저장과 같은 트랜잭션에서 부른다. 혼자 부르면 안 되므로 MANDATORY:
 * 바깥 트랜잭션이 없으면 IllegalTransactionStateException — 초안 없이 케이스만 DRAFTED 가 되는 일을 막는다.
 *
 * 검사 순서: 케이스 잠금 조회(404) → version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 *          → 상태·반려 코드(409 INVALID_CASE_TRANSITION, Case.attachDraft).
 * 허용: ESCALATED(첫 초안), DRAFTED(재생성), REJECTED+NEED_REVISION(보완 반려 뒤 새 초안). 반려 코드는 지운다.
 * 이전 초안 SUPERSEDED 는 str 몫.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttachDraftToCase {

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final CurrentUser currentUser;

    public AttachDraftToCase(CaseRepository cases, CaseHistoryRepository histories, CurrentUser currentUser) {
        this.cases = cases;
        this.histories = histories;
        this.currentUser = currentUser;
    }

    /**
     * @param caseId          케이스 내부 id
     * @param draftId         str 이 저장한 초안 id (V008 FK 가 같은 케이스의 초안인지 막는다)
     * @param expectedVersion 초안 요청 전(AI 호출 전)에 읽은 cases.version. 그 사이 바뀌었으면 409
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
        c.attachDraft(draftId, actorId);
        cases.save(c);
        histories.saveAll(c.takePendingHistories());
    }
}
