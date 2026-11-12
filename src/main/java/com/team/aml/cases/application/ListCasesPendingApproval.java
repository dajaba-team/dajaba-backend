package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.PendingApprovalRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * str 에 주는 공개 조회 — 승인 대기(PENDING_APPROVAL) 케이스를 승인 요청이 오래된 순으로 (승인함 P1, SCRUM-222).
 * CaseRepository.search 는 이력 시각으로 정렬할 수 없고 생성일 기간이 필수라 따로 둔다 (02_structure §3).
 *
 * <p>정렬: 마지막 SUBMIT_FOR_APPROVAL 이력 시각 오름차순, 같으면 케이스 id 오름차순 (계약서 P1 "기본 요청 오래된 순").
 * 검증·반려 정보(str 테이블)와 "직접 담당했던 케이스" 표시(WasCaseAssignee)는 str 이 붙인다.
 *
 * <p>트랜잭션은 REQUIRED·읽기 전용 — str 조회 유스케이스가 REPEATABLE READ 로 감싸 부르면 그 안에서 같은 스냅샷을 읽는다
 * (08_decisions 10/30 조합 방식). 형식 검사(page·size)는 str 컨트롤러 몫이지만, 틀린 값이 오면 여기서도 막는다.
 */
@Service
public class ListCasesPendingApproval {

    /** 계약서 2.3 페이지 크기 상한. */
    public static final int MAX_SIZE = 100;

    private final CaseRepository cases;
    private final UserRefs userRefs;

    public ListCasesPendingApproval(CaseRepository cases, UserRefs userRefs) {
        this.cases = cases;
        this.userRefs = userRefs;
    }

    /**
     * @param page 0부터
     * @param size 1~{@value #MAX_SIZE}
     * @throws IllegalArgumentException page 가 음수이거나 size 가 범위 밖
     */
    @Transactional(readOnly = true)
    public Page<PendingCase> execute(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page 는 0 이상: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 는 1~" + MAX_SIZE + ": " + size);
        }
        Page<PendingApprovalRow> rows = cases.findPendingApproval(PageRequest.of(page, size));
        Map<Long, UserRef> refs = userRefs.of(rows.map(PendingApprovalRow::assigneeId).getContent());
        return rows.map(r -> new PendingCase(r.caseId(), r.caseNo(), r.version(), r.currentDraftId(),
                refs.get(r.assigneeId()), r.submittedAt()));
    }
}
