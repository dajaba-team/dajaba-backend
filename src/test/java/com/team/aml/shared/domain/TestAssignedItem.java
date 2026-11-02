package com.team.aml.shared.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 테스트 전용 AssignedIdEntity 하위 (테이블은 src/test/resources/db/test-migration/V9000). */
@Entity
@Table(name = "test_assigned_items")
@AttributeOverride(name = "id", column = @Column(name = "item_id"))   // str_draft_requests(draft_id)와 같은 경로
public class TestAssignedItem extends AssignedIdEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    protected TestAssignedItem() {}

    public TestAssignedItem(Long id, String name) {
        super(id);
        this.name = name;
    }

    public void rename(String name) { this.name = name; }

    public String getName() { return name; }
}
