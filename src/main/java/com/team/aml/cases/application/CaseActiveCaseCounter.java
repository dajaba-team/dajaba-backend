package com.team.aml.cases.application;

import com.team.aml.cases.domain.AssigneeCaseCount;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.user.application.ActiveCaseCounter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * user 의 ActiveCaseCounter 를 cases 가 구현한다 (미결 33, SCRUM-178).
 * 진행 중 = CaseStatus.active() — 상태 목록은 여기서 다시 적지 않는다. 한 번의 group by 로 센다.
 */
@Service
public class CaseActiveCaseCounter implements ActiveCaseCounter {

    private final CaseRepository cases;

    public CaseActiveCaseCounter(CaseRepository cases) {
        this.cases = cases;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> countActiveByAssignees(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> counts = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        ids.forEach(id -> counts.put(id, 0L));
        for (AssigneeCaseCount row : cases.countByAssignees(ids, CaseStatus.active())) {
            counts.put(row.assigneeId(), row.count());
        }
        return counts;
    }
}
