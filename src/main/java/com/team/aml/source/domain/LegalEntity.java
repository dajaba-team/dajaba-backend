package com.team.aml.source.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** entities 테이블. JPA @Entity와 이름이 겹치지 않도록 클래스명만 LegalEntity로 둔다. */
@Entity
@Immutable
@Table(name = "entities")
public class LegalEntity {

    @Id
    @Column(name = "entity_id", length = 32)
    private String entityId;

    @Column(name = "entity_name", nullable = false, length = 200)
    private String entityName;

    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected LegalEntity() {}

    public String getEntityId() { return entityId; }
    public String getEntityName() { return entityName; }
    public String getEntityType() { return entityType; }
    public Instant getCreatedAt() { return createdAt; }
}
