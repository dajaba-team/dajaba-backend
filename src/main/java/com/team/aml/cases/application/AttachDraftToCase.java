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
 * str 초안 저장과 같은 트랜잭션에서만 부른다(MANDATORY — 바깥 트랜잭션이 없으면 IllegalTransactionStateException).
 *
 * <p><b>부르는 순서: lock → (str) 초안 insert → execute.</b>
 * str_drafts insert 는 FK(case_id) 때문에 cases 행에 KEY SHARE 를 잡고, 여기의 FOR UPDATE 는 그것과 충돌한다.
 * 초안을 먼저 넣고 잠그면 같은 케이스에 동시에 온 두 저장이 서로의 KEY SHARE 를 기다리다 교착(40P01)이 된다.
 * 그래서 str 은 트랜잭션 맨 앞에서 lock 으로 케이스를 먼저 잠근다 — 뒤 요청은 lock 에서 기다렸다가 version 검사에서 409.
 * 같은 트랜잭션이라 execute 의 두 번째 FOR UPDATE 는 바로 통과한다.
 *
 * <p>검사 순서(lock·execute 공통): 케이스 잠금 조회(404) → version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 *          → 상태·반려 코드(409 INVALID_CASE_TRANSITION).
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
     * 초안을 넣기 전에 부른다. 케이스를 잠그고 붙일 수 있는지만 확인한다(아무것도 바꾸지 않음).
     *
     * @param expectedVersion 초안 요청 전(AI 호출 전)에 읽은 cases.version. 그 사이 바뀌었으면 409
     */
    public void lock(long caseId, long expectedVersion) {
        lockAndCheck(caseId, expectedVersion).requireCanAttachDraft();
    }

    /**
     * 초안을 넣은 뒤 부른다. 같은 검사를 한 번 더 하고 연결·이력을 남긴다.
     *
     * @param draftId         str 이 저장한 초안 id (V008 FK 가 같은 케이스의 초안인지 막는다)
     * @param expectedVersion lock 에 넘긴 것과 같은 값
     */
    public void execute(long caseId, long draftId, long expectedVersion) {
        Case c = lockAndCheck(caseId, expectedVersion);
        c.attachDraft(draftId, currentUser.require().userId());
        cases.save(c);
        histories.saveAll(c.takePendingHistories());
    }

    private Case lockAndCheck(long caseId, long expectedVersion) {
        Case c = cases.findForUpdate(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        if (c.getVersion() != expectedVersion) {
            throw new OptimisticLockException("케이스가 그 사이 바뀌었습니다: " + c.getCaseNo()
                    + " (expected " + expectedVersion + ", current " + c.getVersion() + ")");
        }
        long actorId = currentUser.require().userId();
        if (c.getAssigneeId() == null || c.getAssigneeId() != actorId) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        return c;
    }
}
