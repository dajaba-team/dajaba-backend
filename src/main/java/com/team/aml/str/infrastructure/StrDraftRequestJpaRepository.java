package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StrDraftRequestJpaRepository extends JpaRepository<StrDraftRequest, Long>, StrDraftRequestRepository {
    @Override
    @Query(value = "select nextval('ops.str_draft_id_seq')", nativeQuery = true)
    long nextDraftId();
}
