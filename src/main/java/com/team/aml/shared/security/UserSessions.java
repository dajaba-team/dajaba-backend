package com.team.aml.shared.security;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 다른 사람의 로그인 세션 끊기 — 관리자가 역할 변경·비활성화·비밀번호 초기화를 했을 때 (SCRUM-135 ②, 04 §7).
 * maximumSessions(1) 와 같은 SessionRegistry 를 쓴다. 끊긴 세션의 다음 요청은 JsonAuthHandlers.expiredSession() → 401 UNAUTHENTICATED.
 * 지금 트랜잭션이 커밋된 뒤에만 끊는다(롤백이면 그대로). 트랜잭션 밖에서 부르면 IllegalStateException.
 * 서버 메모리 세션이라 서버가 여러 대가 되면 다시 봐야 한다.
 */
@Component
public class UserSessions {

    private final SessionRegistry registry;

    public UserSessions(SessionRegistry registry) {
        this.registry = registry;
    }

    public void expireAll(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("세션 끊기는 트랜잭션 안에서 불러야 해요 — 커밋 뒤에 끊는다");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                expireNow(userId);
            }
        });
    }

    private void expireNow(long userId) {
        // AppUserDetails 는 userId 로만 같은지 본다 — 나머지 칸은 비워도 그 사람 세션이 나온다.
        AppUserDetails principal = new AppUserDetails(userId, null, null, null);
        for (SessionInformation session : registry.getAllSessions(principal, false)) {
            session.expireNow();
        }
    }
}
