package com.team.aml.cases.domain;

/** 담당자 한 명의 케이스 수 — CaseRepository.countByAssignees 결과 한 줄. */
public record AssigneeCaseCount(Long assigneeId, long count) {}
