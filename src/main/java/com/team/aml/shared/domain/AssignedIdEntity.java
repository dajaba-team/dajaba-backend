package com.team.aml.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * 시퀀스로 미리 받은 번호를 id 로 쓰는 테이블의 부모 — str_drafts, str_draft_requests (SCRUM-68, 백엔드 #28 리뷰 11/2).
 * 감사 4칸은 AuditedEntity 가 그대로 채운다. id 는 생성자로 받는다(@GeneratedValue 없음).
 * id 가 이미 있으면 Spring Data save() 는 merge(SELECT 후 덮어쓰기)로 가므로 isNew() 로 "만들고 아직 저장 안 함 = 새 행"을 알린다.
 * → 첫 save() 는 INSERT 한 번, 같은 id 를 새로 만들어 또 저장하면 PK 위반. 저장·조회 뒤에는 새 행이 아니다.
 * id 컬럼 이름이 다르면 하위에 @AttributeOverride(name = "id", column = @Column(name = "draft_id")).
 * 낙관적 락이 필요하면 하위 엔티티에 @Version 을 따로 둔다(str_drafts). 이 클래스는 version 으로 새 행을 판단하지 않는다.
 */
@MappedSuperclass
public abstract class AssignedIdEntity extends AuditedEntity implements Persistable<Long> {

    @Id
    @Column(name = "id")
    private Long id;

    @Transient
    private boolean newEntity = true;

    /** JPA 용. 조회한 엔티티는 @PostLoad 에서 새 행 아님으로 바뀐다. */
    protected AssignedIdEntity() {}

    protected AssignedIdEntity(Long id) {
        if (id == null) throw new IllegalArgumentException("미리 받은 id 가 없습니다");
        this.id = id;
    }

    @PostPersist
    @PostLoad
    void markNotNew() { this.newEntity = false; }

    @Override
    public Long getId() { return id; }

    @Override
    public boolean isNew() { return newEntity; }
}
