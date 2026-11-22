package com.team.aml.cases.domain;

import java.time.Instant;
import java.util.List;

/**
 * 업무 알림 조회 (SCRUM-355). case_history 에서 "나에게 해당하는 것"을 받는 사람 규칙으로 거른다 — 읽기만.
 * 공통: since 뒤 until 까지(since &lt; created_at &le; until — until 은 응답 server_time 이라 다음 조회와 겹치지 않음),
 * 내가 한 일(created_by = 나) 제외.
 *
 * <ul>
 *   <li>{@link Audience#INVESTIGATOR} — ASSIGN 받은 사람 · REASSIGN 받은/넘긴 사람 · APPROVE·REJECT·REPORT 는 케이스의 지금 담당</li>
 *   <li>{@link Audience#APPROVER} — SUBMIT_FOR_APPROVAL, 단 그 케이스를 지금 맡았거나(cases.assignee_id) 받은·넘긴 적이 있으면
 *       (case_history.to_assignee_id·from_assignee_id) 제외. 승인 권한 WasCaseAssignee 와 <b>같은 기준</b> —
 *       V010 전에 배정받고 그 뒤 넘긴 사람은 from 에만 남기 때문(추가 검토 1). 한쪽을 바꾸면 다른 쪽도</li>
 * </ul>
 * V010 전 배정 이력은 담당자 칸이 비어 있어 "= 나" 비교에 걸리지 않고 빠진다.
 */
public interface NotificationRows {

    enum Audience { INVESTIGATOR, APPROVER }

    /** 최신순(created_at DESC, id DESC) limit 줄. */
    List<NotificationRow> find(long me, Audience audience, Instant since, Instant until, int limit);

    /** 같은 조건의 건수, cap 에서 멈춘다. */
    int count(long me, Audience audience, Instant since, Instant until, int cap);
}
