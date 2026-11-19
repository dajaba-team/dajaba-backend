package com.team.aml.cases.domain;

/**
 * 질문 답변 결과 (규약 5.4 상태 4종, 계약서 5.3 A8). 넷 다 화면엔 201 — 에러가 아니고 질문 기록도 남는다.
 * shared {@code AiDtos.AskStatus} 와 같은 이름이지만 cases 가 저장·응답하는 값이라 따로 둔다.
 */
public enum CaseQuestionStatus {
    ANSWERED,      // 답했고 코드 검사를 통과 — answer 있음
    NO_DATA,       // 범위 안 질문인데 facts 에 그 값이 없음
    UNVERIFIED,    // 답은 나왔지만 코드 검사에서 어긋나 내보내지 않음
    OUT_OF_SCOPE   // 이 기능이 답하지 않는 질문 — out_of_scope_reason 있음
}
