package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import org.springframework.data.repository.Repository;

/**
 * domain 의 CaseRepository 를 Spring Data 가 구현하게 한다.
 * JpaRepository 가 아니라 Repository 를 상속해 delete 계열 메서드가 생기지 않게 한다.
 */
public interface CaseJpaRepository extends Repository<Case, Long>, CaseRepository {
}
