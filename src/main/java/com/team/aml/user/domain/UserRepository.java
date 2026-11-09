package com.team.aml.user.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/** 인터페이스는 domain 에, JPA 구현은 infrastructure 에 (레퍼런스 4.1). */
public interface UserRepository {
    Optional<User> findByLoginId(String loginId);
    Optional<User> findById(Long id);
    boolean existsByLoginId(String loginId);
    User save(User user);

    /**
     * 관리자 계정 목록 (SCRUM-135). role 이 null 이면 역할 조건 없음, active 는 항상 조건.
     * 정렬은 display_name 오름차순·같으면 id 오름차순으로 메서드 이름에 고정 — pageable 에 Sort 를 넣지 않는다(두 정렬이 섞인다).
     */
    default Page<User> findPage(Role role, boolean active, Pageable pageable) {
        return role == null
                ? findByActiveOrderByDisplayNameAscIdAsc(active, pageable)
                : findByRoleAndActiveOrderByDisplayNameAscIdAsc(role, active, pageable);
    }

    Page<User> findByActiveOrderByDisplayNameAscIdAsc(boolean active, Pageable pageable);
    Page<User> findByRoleAndActiveOrderByDisplayNameAscIdAsc(Role role, boolean active, Pageable pageable);
}
