package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryRepository;
import org.springframework.data.repository.Repository;

/** 추가만 — Repository 를 상속해 save·delete 계열이 따로 생기지 않게 한다. */
public interface CaseHistoryJpaRepository extends Repository<CaseHistory, Long>, CaseHistoryRepository {
}
