package com.team.aml.user.infrastructure;

import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** domain 의 UserRepository 를 Spring Data 가 구현하게 한다. */
public interface UserJpaRepository extends JpaRepository<User, Long>, UserRepository {
}
