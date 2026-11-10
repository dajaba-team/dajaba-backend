package com.team.aml.cases.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/** 추가만 하는 이력 (01_code_rules 원칙 3). UPDATE·DELETE 메서드를 두지 않는다. */
public interface CaseHistoryRepository {
    <S extends CaseHistory> List<S> saveAll(Iterable<S> histories);
    List<CaseHistory> findByCaseIdOrderByIdAsc(Long caseId);

    /**
     * 상세 화면의 최근 이력 20건, 최신이 먼저 (CASES 명세 API-02 — 전체는 API-11).
     * NOTE 는 빼고 부른다(action = NOTE) — 메모 전문이 상태 이력에 섞이지 않게, 전문은 API-15 (명세 API-11).
     */
    List<CaseHistory> findTop20ByCaseIdAndActionNotOrderByIdDesc(Long caseId, CaseHistoryAction action);

    /** 이력 전체 한 페이지, NOTE 같은 동작 하나를 뺀다 (API-11). */
    Page<CaseHistory> findByCaseIdAndActionNotOrderByIdDesc(Long caseId, CaseHistoryAction action, Pageable pageable);

    /** 이 동작의 마지막 행 — 메모는 마지막 NOTE 행의 comment 가 지금 메모 (API-05). */
    Optional<CaseHistory> findFirstByCaseIdAndActionOrderByIdDesc(Long caseId, CaseHistoryAction action);

    /** 메모 이력 한 페이지, 최신이 먼저 (API-15). 같은 시각도 id 로 순서가 정해진다. */
    Page<CaseHistory> findByCaseIdAndActionOrderByIdDesc(Long caseId, CaseHistoryAction action, Pageable pageable);

    /** id 보다 바로 앞(더 오래된) 행 — 페이지 맨 끝 메모의 before_text 를 페이지 밖에서 찾는다 (API-15). */
    Optional<CaseHistory> findFirstByCaseIdAndActionAndIdLessThanOrderByIdDesc(Long caseId, CaseHistoryAction action,
                                                                               Long id);

    /** 이 사람이 이 케이스를 받은 적이 있나 — ASSIGN·REASSIGN 의 to_assignee_id (V010, 자기 승인 막기). */
    boolean existsByCaseIdAndToAssigneeId(Long caseId, Long toAssigneeId);
}
