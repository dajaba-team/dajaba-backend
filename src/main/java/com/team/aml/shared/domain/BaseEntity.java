package com.team.aml.shared.domain;

import jakarta.persistence.*;

/**
 * 상태가 있는 테이블(사람이 고치는 테이블)의 공통 부모. 감사 컬럼 4개 (레퍼런스 원칙 2, 8.1).
 * created_by / updated_by 는 SessionAuditorAware 가 현재 사용자 id 로 채운다. 배치 스레드는 1L(SYSTEM).
 * 추가만 하는 이력 테이블은 이 클래스가 아니라 CreatedOnlyEntity 를 상속한다 (updated_* 컬럼이 없어 validate 에서 깨짐).
 * 감사 칸은 AuditedEntity 에 있다. 여기는 DB 가 주는 id(IDENTITY)만 (SCRUM-68). 미리 받은 번호를 id 로 쓰면 AssignedIdEntity.
 */
@MappedSuperclass
public abstract class BaseEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() { return id; }
}
