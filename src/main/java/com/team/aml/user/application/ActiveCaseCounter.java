package com.team.aml.user.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 조사역별 진행 중 담당 건수 (미결 33, SCRUM-178). 계정 관리(SCRUM-135 ②)가 쓴다.
 * user → cases 참조는 막혀 있으므로(ArchitectureTest) 인터페이스는 user 에 두고 구현은 cases 가 한다.
 * "진행 중"이 어떤 상태인지는 cases 가 정한다(CaseStatus.active) — 여기서는 모른다.
 */
public interface ActiveCaseCounter {

    /**
     * 계정 목록용. 넘긴 id 는 0건이어도 모두 키로 들어간다. 목록이 null 이거나 비었으면 빈 지도(DB 를 읽지 않음).
     * 목록 안의 null id 는 건너뛴다.
     */
    Map<Long, Long> countActiveByAssignees(Collection<Long> userIds);

    /** 역할 변경 검사용 — 0 보다 크면 409 ASSIGNEE_HAS_ACTIVE_CASES. */
    default long countActive(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return countActiveByAssignees(List.of(userId)).getOrDefault(userId, 0L);
    }
}
