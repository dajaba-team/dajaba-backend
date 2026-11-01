package com.team.aml.str.domain;

/** 검증 결과 상태. AI 규약 그대로 4개 (안건 9). 승인 요청을 여는 것은 PASSED 하나뿐이다. */
public enum VerificationStatus {
    PASSED,              // 코드 검사 5개 통과 + 평가자 5개 모두 4점 이상
    CODE_FAILED,         // 코드 검사(금액·날짜 등)에서 걸림
    LLM_FAILED,          // 평가자 점수가 기준 미달
    VERIFY_UNAVAILABLE   // 평가자 호출 실패 = "검증 못 함". 실패는 아니지만 승인 요청도 안 된다
}
