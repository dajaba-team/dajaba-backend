package com.team.aml.cases.api;

/**
 * POST /cases/{case_no}/questions (계약서 5.3 A8). version 은 받지 않는다 — 케이스를 바꾸지 않아 동시 수정 충돌이 없다.
 * 길이(NFC 뒤 UTF-16 1~300)·공백 검사는 도메인 CaseQuestion.requireValidQuestion 한 곳(400 details.question) —
 * 꺼져 있을 때 404 가 400 보다 먼저 나가게 @Valid 를 쓰지 않는다.
 */
public record AskQuestionRequest(String question) {
}
