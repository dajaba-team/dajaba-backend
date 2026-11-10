package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.NotAssigneeException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;

/**
 * 화면에서 케이스를 바꾸는 유스케이스(배정·에스컬레이션·종료·메모)의 공통 앞뒤. 부르는 쪽 트랜잭션 안에서만 쓴다.
 * 검사 순서: 케이스 번호로 조회(404 CASE_NOT_FOUND) → 화면이 읽은 version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 * → 상태(엔티티가 409 INVALID_CASE_TRANSITION). AttachDraftToCase 와 같은 순서.
 * 요청 version 비교 뒤에도 커밋 때 @Version 이 한 번 더 막는다 — 동시에 온 두 요청은 뒤가 409 (CASES 명세 §8.3).
 */
@Component
class CaseChanges {

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final UserRefs userRefs;

    CaseChanges(CaseRepository cases, CaseHistoryRepository histories, UserRefs userRefs) {
        this.cases = cases;
        this.histories = histories;
        this.userRefs = userRefs;
    }

    Case load(String caseNo, long expectedVersion) {
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        if (c.getVersion() != expectedVersion) {
            throw new OptimisticLockException("케이스가 그 사이 바뀌었습니다: " + caseNo
                    + " (expected " + expectedVersion + ", current " + c.getVersion() + ")");
        }
        return c;
    }

    void requireAssignee(Case c, long actorId) {
        if (c.getAssigneeId() == null || c.getAssigneeId() != actorId) {
            throw new NotAssigneeException(c.getCaseNo());
        }
    }

    /** 저장 → 이력 → 공통 변경 응답. @Version·updated_at 은 flush 때 정해지므로 flush 뒤 값을 돌려준다. */
    CaseChangeResult save(Case c) {
        Case saved = cases.saveAndFlush(c);
        histories.saveAll(saved.takePendingHistories());
        return result(saved);
    }

    /**
     * 칸은 그대로 두고 version 만 올린 뒤 이력을 넣는다 — 메모처럼 cases 행이 안 바뀌는 변경 (bumpVersion).
     * 이력의 case_version_before 는 업무 메서드를 부를 때 이미 정해졌다(올리기 전 값).
     */
    CaseChangeResult bumpAndRecord(Case c) {
        cases.bumpVersion(c);
        histories.saveAll(c.takePendingHistories());
        return result(c);
    }

    /** 공통 변경 응답. 바뀐 게 없을 때(no-op)도 이 모양. */
    CaseChangeResult result(Case c) {
        return new CaseChangeResult(c.getCaseNo(), c.getStatus(), userRefs.of(c.getAssigneeId()),
                c.getRejectionReasonCode(), c.getCurrentDraftId(), c.getVersion(),
                c.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS));   // 시스템 시각은 초 단위 (계약서 2.4)
    }
}
