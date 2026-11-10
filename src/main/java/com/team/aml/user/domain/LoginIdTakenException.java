package com.team.aml.user.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 LOGIN_ID_TAKEN — 발급하려는 login_id 가 이미 있다. 동시 발급으로 DB unique 에 걸려도 이것 (SCRUM-135 ②). */
public class LoginIdTakenException extends DomainException {

    public LoginIdTakenException(String loginId) {
        super("이미 있는 아이디입니다: " + loginId, Map.of("login_id", loginId));
    }
}
