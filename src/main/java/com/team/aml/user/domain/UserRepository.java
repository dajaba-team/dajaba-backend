package com.team.aml.user.domain;

import java.util.Optional;

/** 인터페이스는 domain 에, JPA 구현은 infrastructure 에 (레퍼런스 4.1). */
public interface UserRepository {
    Optional<User> findByLoginId(String loginId);
    Optional<User> findById(Long id);
    boolean existsByLoginId(String loginId);
    User save(User user);
}
