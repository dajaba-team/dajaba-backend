package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 추가만 — Repository 를 상속해 save·delete 계열이 따로 생기지 않게 한다. */
public interface CaseHistoryJpaRepository extends Repository<CaseHistory, Long>, CaseHistoryRepository {

    /**
     * case_id 가 앞인 인덱스(case_history_case_action_idx)로 그 케이스 행만 본다.
     * V010 CHECK 가 담당자 칸을 ASSIGN·REASSIGN 행에만 허용하므로 action 조건 없이도 같은 결과다.
     */
    @Override
    @Query("""
            select count(h) > 0 from CaseHistory h
             where h.caseId = :caseId
               and (h.toAssigneeId = :userId or h.fromAssigneeId = :userId)
            """)
    boolean existsAssigneeHistory(@Param("caseId") Long caseId, @Param("userId") Long userId);
}
