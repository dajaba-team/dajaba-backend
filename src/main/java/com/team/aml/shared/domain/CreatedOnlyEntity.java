package com.team.aml.shared.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 사람이 추가만 하는 이력 테이블(case_history, str_revisions, approvals …)의 부모 (레퍼런스 원칙 3).
 * ai_call_logs 는 created_by 없이 created_at 만이라 상속하지 않는다 (BatchRun 과 같음, 레퍼런스 3.4).
 * updated_* 가 없다. UPDATE·DELETE 메서드도 없다 — 리포지토리에 만들지 않는다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
}
