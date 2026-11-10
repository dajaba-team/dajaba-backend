package com.team.aml.user.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 USER_NOT_FOUND — 케이스 배정 대상(login_id)이 없을 때 처음 쓴다 (CASES 명세 API-03, 03_shared §2). */
public class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(String loginId) {
        super("사용자가 없습니다: " + loginId, Map.of("login_id", loginId));
    }

    /** 계정 관리 대상(id)이 없을 때 (SCRUM-135 ②). */
    public UserNotFoundException(long id) {
        super("사용자가 없습니다: id=" + id, Map.of("id", id));
    }
}
