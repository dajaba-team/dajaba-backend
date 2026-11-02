package com.team.aml.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 감사 컬럼 4개만 가진 부모 (레퍼런스 원칙 2, 8.1). id 는 없다 — 직접 상속하지 않는다 (SCRUM-68).
 * DB 가 id 를 주는 테이블은 BaseEntity, 시퀀스로 미리 받은 번호를 id 로 쓰는 테이블은 AssignedIdEntity 를 상속한다.
 * created_by / updated_by 는 SessionAuditorAware 가 현재 사용자 id 로 채운다. 세션이 없으면 1L(SYSTEM).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditedEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    public Instant getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
}
