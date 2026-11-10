package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.VerificationResult;
import com.team.aml.str.domain.VerificationResultRepository;
import org.springframework.data.repository.Repository;

/**
 * domain 의 VerificationResultRepository 를 Spring Data 가 구현하게 한다.
 * JpaRepository 가 아니라 Repository — delete 계열이 생기지 않게 (V008 이 UPDATE·DELETE 권한 회수, StrDraftRequestJpaRepository 와 같음).
 * 최신 검증 조회는 LatestVerificationJdbcRepository(S2) 가 한다.
 */
public interface VerificationResultJpaRepository extends Repository<VerificationResult, Long>, VerificationResultRepository {
}
