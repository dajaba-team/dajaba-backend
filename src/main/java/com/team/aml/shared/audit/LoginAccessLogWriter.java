package com.team.aml.shared.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인·로그아웃 기록 — shared/security 만 부른다(ArchitectureTest). 팀원 코드는 쓸 일이 없다.
 * 별도 트랜잭션(REQUIRES_NEW). 이 메서드는 저장 실패를 그대로 올리고, 응답을 지키는 건 호출하는 security 쪽이다
 * (실패해도 로그인·로그아웃 응답은 그대로 + 경고 로그, login_id 는 로그에 쓰지 않음).
 * LOGIN_FAIL 은 인증까지 간 실패(401)만 — 400 형식 오류는 부르지 않는다 (11/2).
 */
@Component
public class LoginAccessLogWriter {

    private final AccessLogRepository repository;

    public LoginAccessLogWriter(AccessLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginOk(long userId, String ip) {
        repository.save(AccessLog.create(AccessLogAction.LOGIN_OK, userId, null, null, null, ip));
    }

    /** loginIdAttempted 는 64자 넘으면 앞 64자만 저장. user_id 는 null. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginFail(String loginIdAttempted, String ip) {
        repository.save(AccessLog.create(AccessLogAction.LOGIN_FAIL, null, loginIdAttempted, null, null, ip));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logout(long userId, String ip) {
        repository.save(AccessLog.create(AccessLogAction.LOGOUT, userId, null, null, null, ip));
    }
}
