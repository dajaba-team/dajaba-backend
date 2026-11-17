package com.team.aml.str.domain;

import java.util.Optional;

/** 확정 보고서 저장소 (ops.str_reports, V015). 지우는 메서드는 두지 않는다. */
public interface StrReportRepository {

    StrReport save(StrReport report);

    /** 초안당 하나 (V015 draft_id unique). */
    Optional<StrReport> findByDraftId(Long draftId);

    /** 문서번호 일련번호. ops.str_report_no_seq 에서 받는다 — 승인 트랜잭션 안에서. */
    long nextReportSeq();
}
