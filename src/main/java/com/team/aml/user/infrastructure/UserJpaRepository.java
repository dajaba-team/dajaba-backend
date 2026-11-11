package com.team.aml.user.infrastructure;

import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** domain 의 UserRepository 를 Spring Data 가 구현하게 한다. */
public interface UserJpaRepository extends JpaRepository<User, Long>, UserRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdate(@Param("id") Long id);
}