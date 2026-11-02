package com.team.aml.shared.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** 테스트 전용. save() 가 persist(INSERT) 로 가는지 merge 로 가는지를 본다. */
public interface TestAssignedItemRepository extends JpaRepository<TestAssignedItem, Long> {
}
