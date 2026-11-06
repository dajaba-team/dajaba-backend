package com.team.aml.cases.application;

/** 화면에 보이는 사람 — 담당자·이력 작성자. 내부 users.id 는 내보내지 않는다 (계약서 5.2 assignee 모양). */
public record UserRef(String loginId, String displayName) {
}
