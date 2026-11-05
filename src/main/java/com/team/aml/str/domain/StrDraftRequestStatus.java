package com.team.aml.str.domain;

/** 초안 요청 기록 상태 (str_draft_requests.status). REQUESTED 에서만 나머지로 한 번 바뀐다. */
public enum StrDraftRequestStatus {
    REQUESTED,   // AI 부르기 전 선점. 케이스마다 하나
    SUCCEEDED,   // 초안 저장됨
    FAILED,      // AI 실패가 확인됨
    EXPIRED      // 오래된 요청 정리
}
