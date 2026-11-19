package com.team.aml.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** UserSessions 가 세션을 userId 와 login_id 로 찾는다 — Redis 세션 색인은 login_id(principal name)라서 (SCRUM-273). */
class UserSessionsLoginIdTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 정상_login_id로만_찾는_저장소에도_login_id가_넘어간다() {
        List<String> seen = new ArrayList<>();
        SessionRegistryImpl byName = new SessionRegistryImpl() {
            @Override
            public List<SessionInformation> getAllSessions(Object principal, boolean includeExpired) {
                seen.add(((AppUserDetails) principal).getUsername());
                return List.of();
            }
        };
        UserSessions sessions = new UserSessions(byName, id -> Optional.of("inv42"));
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(42L);
        assertThat(seen).as("커밋 전엔 안 찾음").isEmpty();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(seen).containsExactly("inv42");
    }

    @Test
    void 경계_없는_사용자면_끊기를_걸지_않는다() {
        List<Object> seen = new ArrayList<>();
        SessionRegistryImpl registry = new SessionRegistryImpl() {
            @Override
            public List<SessionInformation> getAllSessions(Object principal, boolean includeExpired) {
                seen.add(principal);
                return List.of();
            }
        };
        UserSessions sessions = new UserSessions(registry, id -> Optional.empty());
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(999L);
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(seen).isEmpty();
    }
}
