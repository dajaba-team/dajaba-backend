package com.team.aml.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Redis 가 안 되면 커밋 뒤 끊기가 실패한다 → SessionStoreUnavailableException.afterCommit → 503 SESSION_STORE_UNAVAILABLE,
 * 메시지는 "변경은 저장됐지만 … 끊지 못했습니다", details.change_saved=true. DB 변경은 이미 커밋됐다.
 * 그 세션은 Redis 가 돌아오면 다시 쓰인다. 같은 버튼을 다시 누르면 끊기지 않는다(같은 역할 400 · 이미 비활성 200) —
 * 다른 값으로 바꿨다 되돌리거나 초기화를 한 번 더 해야 끊긴다(04 §7, 08_decisions 미결 34).
 */
@Component
public class UserSessions {

    private static final Logger log = LoggerFactory.getLogger(UserSessions.class);

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
            // 부르는 쪽이 대상 행을 잠근 뒤라 사실상 안 생긴다. 생기면 "안 끊겼는데 흔적 없음"이 되지 않게 남긴다
            log.warn("session expire skipped: user not found userId={}", userId);
            return;
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
                throw SessionStoreUnavailableException.afterCommit(e);
            }
            throw e;
        }
    }
}
