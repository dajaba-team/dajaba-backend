package com.team.aml.user.api;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 MOCK_LOGIN_FAILED — 시드 사용자가 아니거나 비활성. (진짜 로그인은 401 LOGIN_FAILED 로 사유를 숨긴다.) */
public class MockLoginFailedException extends DomainException {
    public MockLoginFailedException(String loginId) {
        super("로그인할 수 없는 사용자입니다: " + loginId + " (DevUserSeeder 의 admin/inv01/apr01/inv02 만)",
                Map.of("login_id", loginId));
    }
}
