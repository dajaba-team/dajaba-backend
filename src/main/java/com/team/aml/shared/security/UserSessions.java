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
 *
 * 세션은 userId 와 login_id 를 둘 다 넣은 주체로 찾는다 — 메모리(SessionRegistryImpl)는 AppUserDetails.equals(userId)로,
 * Redis(SpringSessionBackedSessionRegistry)는 principal name(= login_id) 색인으로 찾기 때문 (SCRUM-273).
 * login_id 는 부르는 쪽 트랜잭션 안에서 읽고(대상 행이 잠긴 상태), 끊기만 커밋 뒤에 한다.
 * Redis 가 안 되면 커밋 뒤 끊기가 실패한다 → SessionStoreUnavailableException → 503 SESSION_STORE_UNAVAILABLE.
 * DB 변경은 이미 커밋됐다. 그 세션은 Redis 가 돌아오기 전엔 못 쓰지만 돌아오면 다시 쓰여서, 관리자가 한 번 더 바꿔 끊는다(08_decisions).
 */
@Component
public class UserSessions {

    private final SessionRegistry registry;
    private final LoginIdLookup loginIds;

    public UserSessions(SessionRegistry registry, LoginIdLookup loginIds) {
        this.registry = registry;
        this.loginIds = loginIds;
    }

    public void expireAll(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("세션 끊기는 트랜잭션 안에서 불러야 해요 — 커밋 뒤에 끊는다");
        }
        String loginId = loginIds.loginIdOf(userId).orElse(null);
        if (loginId == null) {
            return;   // 없는 사용자 — 로그인한 적도, 끊을 세션도 없다
        }
        AppUserDetails principal = new AppUserDetails(userId, loginId, null, null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                expireNow(principal);
            }
        });
    }

    private void expireNow(AppUserDetails principal) {
        try {
            for (SessionInformation session : registry.getAllSessions(principal, false)) {
                session.expireNow();
            }
        } catch (RuntimeException e) {
            if (SessionStoreUnavailableException.isCausedBy(e)) {
                throw new SessionStoreUnavailableException(e);
            }
            throw e;
        }
    }
}
