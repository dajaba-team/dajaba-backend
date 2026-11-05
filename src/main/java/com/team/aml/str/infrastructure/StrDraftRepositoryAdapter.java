package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * StrDraftRepository 구현. 저장 순서가 정해져 있어서 Spring Data save() 를 그대로 쓰지 않는다.
 * str_drafts.current_revision_id → str_revisions, str_revisions.draft_id → str_drafts 로 서로 가리키므로
 * ① 초안(current_revision_id 없음) → ② 수정본(draft_id 채움, IDENTITY 로 id 받음) → ③ 초안의 current_revision_id UPDATE.
 * ③은 JPQL UPDATE 로 바로 쓴다 — 버전·updated_at 을 건드리지 않는다. 부르는 유스케이스가 트랜잭션을 연다(@Transactional 은 application 에만).
 */

@Repository
public class StrDraftRepositoryAdapter implements StrDraftRepository {

    private final StrDraftJpaRepository jpa;
    private final EntityManager em;

    StrDraftRepositoryAdapter(StrDraftJpaRepository jpa, EntityManager em) {
        this.jpa = jpa;
        this.em = em;
    }

    @Override
    public StrDraft save(StrDraft draft) {
        if (draft.isNew()) {
            // 재생성: 같은 트랜잭션에서 예전 초안을 supersede() 했으면 그 UPDATE 를 먼저 내보낸다.
            // persist 는 수정본 1번(IDENTITY) 때문에 INSERT 를 바로 내보내므로, flush 없이는
            // 새 초안 INSERT 가 SUPERSEDED UPDATE 보다 먼저 나가 str_drafts_one_active_uq(케이스당 ACTIVE 하나)에 걸린다 (#53 리뷰)
            em.flush();
            em.persist(draft);                 // 수정본 1번도 같이 (cascade PERSIST)
        } else if (!em.contains(draft)) {
            throw new IllegalStateException("읽어 온 트랜잭션 밖의 초안은 저장하지 않는다: " + draft.getId());
        }
        em.flush();                            // ①·② INSERT — 수정본 id 가 생긴다
        Long revisionId = draft.syncCurrentRevisionId();
        em.createQuery("update StrDraft d set d.currentRevisionId = :revisionId where d.id = :id")
                .setParameter("revisionId", revisionId)
                .setParameter("id", draft.getId())
                .executeUpdate();              // ③ 벌크 UPDATE 는 @Version·@PreUpdate 를 거치지 않는다
        return draft;
    }
    @Override
    public Optional<StrDraft> findById(Long draftId) {
        return jpa.findById(draftId);
    }

    @Override
    public Optional<StrDraft> findActiveByCaseId(Long caseId) {
        return jpa.findByCaseIdAndStatus(caseId, StrDraftStatus.ACTIVE);
    }
}

