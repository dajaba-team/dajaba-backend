package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import jakarta.persistence.OptimisticLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 케이스를 잠그고 version 만 1 올린다 (3주차 리뷰 할 일 3, NFR-APR-02).
 * 케이스 상태·칸은 안 바꾸고 이력도 안 남긴다. str 의 수정본 저장처럼 "초안 행만 바뀌는" 작업이 같은 트랜잭션에서 부른다.
 * 그러면 같은 케이스에 동시에 들어온 승인 요청·종결과 version 으로 부딪혀 한쪽이 409 CONCURRENT_UPDATE 가 된다.
 *
 * 권한(담당자 여부 등)은 부르는 쪽 유스케이스가 먼저 본다 — 이 유스케이스는 누가 무엇을 하는지 모른다.
 * MANDATORY: str 트랜잭션 안에서만. 혼자 부르면 IllegalTransactionStateException.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BumpCaseVersion {

    private final CaseRepository cases;

    public BumpCaseVersion(CaseRepository cases) {
        this.cases = cases;
    }

    /**
     * @param expectedVersion 화면·요청이 들고 온 cases.version(case_version). 다르면 409
     */
    public void execute(long caseId, long expectedVersion) {
        Case c = cases.findForUpdate(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        if (c.getVersion() != expectedVersion) {
            throw new OptimisticLockException("케이스가 그 사이 바뀌었습니다: " + c.getCaseNo()
                    + " (expected " + expectedVersion + ", current " + c.getVersion() + ")");
        }
        cases.bumpVersion(c);
    }
}
