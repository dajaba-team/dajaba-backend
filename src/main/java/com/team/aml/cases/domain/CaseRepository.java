package com.team.aml.cases.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 인터페이스는 domain 에, JPA 구현은 infrastructure 에 (레퍼런스 4.1, UserRepository 와 같음).
 * 케이스는 지우지 않는다 — delete 메서드를 두지 않는다 (V006 에서 DELETE 회수).
 * 저장 뒤 이력은 case.takePendingHistories() 를 CaseHistoryRepository 로 같은 트랜잭션에서 넣는다.
 */
public interface CaseRepository {
    Case save(Case c);

    /**
     * 저장하고 바로 DB 에 반영한다(flush). 상태가 바뀐 케이스의 @Version 은 flush 때 오르므로,
     * 같은 트랜잭션 안에서 새 cases.version 을 돌려줘야 하는 유스케이스(AttachDraftToCase 등, SCRUM-141)가 쓴다.
     */
    Case saveAndFlush(Case c);
    Optional<Case> findById(Long id);

    /**
     * 비관적 쓰기 잠금(SELECT … FOR UPDATE)으로 읽는다. 트랜잭션이 끝날 때까지 같은 케이스를 바꾸려는
     * 다른 트랜잭션은 기다린다. 다른 도메인(str)과 함께 케이스를 바꾸는 유스케이스(AttachDraftToCase 등)가 쓴다.
     * 낙관적 락(@Version)은 그대로 — 잠금은 "동시에", version 은 "AI 호출 전에 읽은 뒤로 바뀌었는지"를 본다.
     */
    Optional<Case> findForUpdate(Long id);

    /**
     * 상태·칸은 그대로 두고 cases.version 만 1 올린다 (PESSIMISTIC_FORCE_INCREMENT, 트랜잭션이 롤백되면 같이 되돌아간다).
     * str 이 케이스 행을 안 바꾸는 작업(수정본 저장 등)을 할 때 부른다 — 동시에 들어온 승인 요청 같은
     * "서로 다른 행만 바꾸는 두 요청"이 같은 케이스 version 에서 부딪히게 하려는 것 (NFR-APR-02, 미결 20).
     */
    void bumpVersion(Case c);

    /**
     * 케이스 생성 배치 시작을 줄 세운다(advisory lock, 트랜잭션 끝에 풀림). 같은 날짜가 동시에 시작해
     * RUNNING 두 행이 생기지 않게 OpenCasesRecorder.start 가 RUNNING 확인 전에 부른다 (SCRUM-238).
     */
    void lockOpenCasesStart();

    /**
     * 케이스 생성 배치 한 건(batch_run_id)을 "지금 내가 돌린다"고 잡는다 — 기다리지 않는 advisory lock, 트랜잭션 끝에 풀림.
     * 큐 리스너와 앱 안 실행기가 같은 실행을 동시에 돌지 않게 RunOpenCasesOnce 가 부른다(SCRUM-276, #130 리뷰).
     * 실행하던 앱이 죽으면 연결이 끊기며 같이 풀려서 브로커 재전달 때 다시 잡을 수 있다.
     *
     * @return 잡았으면 true, 다른 트랜잭션이 이미 잡고 있으면 false
     */
    boolean tryLockOpenCasesRun(long batchRunId);
    Optional<Case> findByCaseNo(String caseNo);
    boolean existsByAlertId(Long alertId);

    /**
     * 목록 (API-01). statuses 는 비우지 않고 넘긴다(전체면 CaseStatus 전부). assigneeId 는 null 이면 조건 없음.
     * 생성 시각은 createdFrom 이상 createdTo 미만 — 둘 다 null 불가(PostgreSQL 이 null timestamptz 파라미터 타입을 못 정한다).
     * 정렬은 pageable 의 Sort — 허용 컬럼은 ListCases 가 정한다.
     */
    Page<Case> search(Collection<CaseStatus> statuses, Long assigneeId, Instant createdFrom, Instant createdTo,
                      Pageable pageable);

    /**
     * 목록 점수 순 (API-01 sort=score, SCRUM-236). search 와 같은 조건, 생성 당시 점수(opened_score) 순.
     * 점수 없는 케이스는 오름·내림 모두 맨 뒤, 같은 점수는 created_at DESC, id DESC. pageable 의 Sort 는 쓰지 않는다
     * (Spring Data 가 문자열 쿼리에 nulls last 를 붙이지 않아서 정렬을 쿼리에 직접 적는다).
     */
    Page<Case> searchByScore(Collection<CaseStatus> statuses, Long assigneeId, Instant createdFrom, Instant createdTo,
                             boolean ascending, Pageable pageable);

    /**
     * 담당자별 케이스 수 (미결 33, SCRUM-178). assigneeIds·statuses 는 비우지 않고 넘긴다.
     * 한 건도 없는 담당자는 결과에 없다 — 0 채우기는 부르는 쪽(ActiveCaseCounter 구현) 몫.
     */
    List<AssigneeCaseCount> countByAssignees(Collection<Long> assigneeIds, Collection<CaseStatus> statuses);

    /**
     * 케이스 번호 일련번호 (ops.case_no_seq, V006). 번호 형식은 DisplayNumber.caseNo 한 곳.
     * 시퀀스라 롤백돼도 번호는 되돌아가지 않는다(빈 번호가 생길 수 있음 — 레퍼런스 8.7, MAX+1 금지).
     */
    long nextCaseNoSeq();

    /**
     * 승인 대기(PENDING_APPROVAL) 케이스 한 페이지 — str 승인함 P1 (SCRUM-222).
     * submittedAt 은 마지막 SUBMIT_FOR_APPROVAL 이력 시각(보완 뒤 다시 요청하면 새 시각).
     * 정렬은 쿼리에 고정 — submittedAt 오름차순, 같으면 id 오름차순. pageable 은 page·size 만 쓴다(Sort 를 넣지 않는다).
     */
    Page<PendingApprovalRow> findPendingApproval(Pageable pageable);
}
