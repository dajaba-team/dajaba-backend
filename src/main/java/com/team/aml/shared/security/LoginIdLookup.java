package com.team.aml.shared.security;

import java.util.Optional;

/**
 * 사용자 id → login_id. Redis 세션은 principal name(= AppUserDetails.getUsername() = login_id)으로 색인돼서
 * UserSessions 가 세션을 찾을 때 쓴다 (SCRUM-273). shared 는 user 를 모르므로 구현은 user/infrastructure.
 */
public interface LoginIdLookup {

    /** 없는 사용자면 empty. 부르는 쪽 트랜잭션 안에서 읽는다. */
    Optional<String> loginIdOf(long userId);
}
