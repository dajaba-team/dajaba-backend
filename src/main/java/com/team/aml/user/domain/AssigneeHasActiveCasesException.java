package com.team.aml.user.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 ASSIGNEE_HAS_ACTIVE_CASES — 진행 중 담당이 남은 조사역을 승인자로 바꾸려 함 (SCRUM-135 ②). */
public class AssigneeHasActiveCasesException extends DomainException {

    public AssigneeHasActiveCasesException(long userId, long activeCaseCount) {
        super("진행 중인 담당 케이스가 " + activeCaseCount + "건 남아 있습니다: id=" + userId,
                Map.of("active_case_count", activeCaseCount));
    }
}
