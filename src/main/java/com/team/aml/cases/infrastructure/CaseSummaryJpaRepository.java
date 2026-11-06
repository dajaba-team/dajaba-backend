package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.CaseSummary;
import com.team.aml.cases.domain.CaseSummaryRepository;
import org.springframework.data.repository.Repository;

/** 추가만 — Repository 를 상속해 delete 계열이 생기지 않게 한다 (CaseHistoryJpaRepository 와 같음). */
public interface CaseSummaryJpaRepository extends Repository<CaseSummary, Long>, CaseSummaryRepository {
}
