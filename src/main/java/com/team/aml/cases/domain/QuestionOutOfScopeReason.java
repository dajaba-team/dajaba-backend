package com.team.aml.cases.domain;

/** 범위 밖 사유 (규약 5.4). {@link CaseQuestionStatus#OUT_OF_SCOPE} 일 때만 값이 있다. 안내 문구는 화면이 정한다. */
public enum QuestionOutOfScopeReason {
    EDIT_REQUEST,   // 고쳐 달라는 요청
    OUTSIDE_CASE,   // 이 케이스 밖 정보
    JUDGMENT        // 세탁 여부·보고 여부 같은 판단 요구
}
