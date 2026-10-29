package com.team.aml.str.domain;

public enum StrDraftStatus {
    ACTIVE,      // 사용 중: 수정·검증 가능
    SUPERSEDED,  // 대체됨: 같은 케이스에서 다시 만들었거나 케이스가 종료됨
    LOCKED       // 잠김: 승인됨, 되돌릴 수 없음
}
