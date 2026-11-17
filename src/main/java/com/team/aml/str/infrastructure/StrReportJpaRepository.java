package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * domain 의 StrReportRepository 를 Spring Data 가 구현하게 한다.
 * JpaRepository 가 아니라 Repository — delete 계열이 생기지 않게 (V015 가 DELETE 권한 회수, 5년 보존).
 */
public interface StrReportJpaRepository extends Repository<StrReport, Long>, StrReportRepository {

    @Override
    @Query(value = "select nextval('ops.str_report_no_seq')", nativeQuery = true)
    long nextReportSeq();
}
