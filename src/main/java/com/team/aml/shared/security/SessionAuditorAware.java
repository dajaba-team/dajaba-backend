package com.team.aml.shared.security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** BaseEntity 의 created_by / updated_by 에 들어갈 값 (레퍼런스 9.4). 로그인 없으면 SYSTEM(1). */
@Component("sessionAuditorAware")
public class SessionAuditorAware implements AuditorAware<Long> {

    private final CurrentUser currentUser;

    public SessionAuditorAware(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Long> getCurrentAuditor() {
        return Optional.of(currentUser.idOrSystem());
    }
}
