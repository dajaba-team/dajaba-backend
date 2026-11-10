package com.team.aml.user.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/**
 * 409 ACCOUNT_CHANGE_NOT_ALLOWED — 관리자 본인(SELF)·SYSTEM 계정은 계정 관리(재활성화, 다음 PR 에서 역할 변경·비활성화·비밀번호 초기화) 대상이 아니다.
 * 화면은 버튼을 숨기지만 서버도 막는다 (SCRUM-135 ②, 04 §7). 다른 관리자는 막지 않는다.
 */
public class AccountChangeNotAllowedException extends DomainException {

    public enum Reason { SELF, SYSTEM }

    public AccountChangeNotAllowedException(long userId, Reason reason) {
        super("이 계정은 바꿀 수 없습니다: id=" + userId + " (" + reason + ")", Map.of("reason", reason.name()));
    }
}
