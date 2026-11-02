package com.team.aml.shared.audit;

/**
 * access_logs.action — 10개 (계정·권한 §1, 미결 10, 서식 안건 4, SCRUM-19). V004_3 CHECK 와 같은 목록. 16자 안.
 * 밖에서 문자열로 넣지 않는다 — AccessLogWriter·LoginAccessLogWriter 의 action별 메서드만 쓴다.
 */
public enum AccessLogAction {
    VIEW,
    STR_VIEW,
    PDF_DOWNLOAD,
    EXPORT,
    LOGIN_OK,
    LOGIN_FAIL,
    LOGOUT,
    USER_CREATED,
    ROLE_CHANGED,
    USER_DEACTIVATED
}
