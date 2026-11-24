package com.team.aml.str.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.user.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STR 초안 생성의 뒤쪽 절반 (S1 비동기). 요청 기록(REQUESTED)은 {@link RequestStrDraft#submit} 이 이미 커밋했고,
 * 여기서는 "AI 호출 → 저장"만 한다 — 동기 S1 의 ②·③과 같은 코드(AiClient.createDraft → SaveStrDraft)를 부른다.
 * {@code str.draft.generate} 큐 받는 쪽, 또는 큐가 꺼졌을 때 앱 안 실행기가 부른다(StrDraftTrigger).
 *
 * <ol>
 *   <li>요청이 REQUESTED 가 아니면 건너뜀 — 같은 메시지가 두 번 와도, 이미 끝났거나 EXPIRED 로 닫혔어도 안전(§14)</li>
 *   <li>AI 에는 <b>저장해 둔 요청 본문 그대로</b>(request_snapshot) — 같은 draft_id 재전송 규칙(미결 21)</li>
 *   <li>요청한 조사역(created_by)으로 실행 — 저장 경로(AttachDraftToCase)가 로그인 사용자를 담당자와 대조하고 이력에 남긴다.
 *       기다리는 사이 담당이 바뀌었으면 403 NOT_ASSIGNEE 로 실패(맞는 동작)</li>
 *   <li>케이스 버전은 요청 때 값이 아니라 저장 직전 값 — 기다리는 사이 메모 저장 등으로 버전이 올라도 저장한다.
 *       종료·상신 취소처럼 초안을 붙일 수 없는 상태면 AttachDraftToCase 가 지금처럼 거절한다</li>
 *   <li>실패하면 요청 FAILED (케이스 그대로). AI 가 같은 draft_id 를 아직 만드는 중(DRAFT_IN_PROGRESS)이면 실패로 남기지 않고
 *       예외를 올린다 — 큐는 재시도, 앱 안 경로는 로그만</li>
 * </ol>
 * 같은 앱 안에서 같은 draft_id 가 겹치면 한 번만 돈다(확정본 PDF 와 같은 모양). 로그에는 초안 번호·예외 종류만(NFR-COM-04).
 */
@Service
public class GenerateStrDraft {

    private static final Logger log = LoggerFactory.getLogger(GenerateStrDraft.class);

    static final String AI_DRAFT_IN_PROGRESS = "DRAFT_IN_PROGRESS";

    private final StrDraftRequestRepository requests;
    private final CaseRepository cases;
    private final AiClient aiClient;
    private final SaveStrDraft saveStrDraft;
    private final ObjectMapper om;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public GenerateStrDraft(StrDraftRequestRepository requests, CaseRepository cases, AiClient aiClient,
                            SaveStrDraft saveStrDraft, ObjectMapper om, PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.cases = cases;
        this.aiClient = aiClient;
        this.saveStrDraft = saveStrDraft;
        this.om = om;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 초안을 저장했으면 true. 할 일이 없었으면(REQUESTED 아님·이미 도는 중) false. 실패는 FAILED 기록 뒤 예외 */
    public boolean execute(long draftId) {
        if (!running.add(draftId)) {
            log.info("초안 생성 중복 작업 건너뜀 draft_no={}", DisplayNumber.draftNo(draftId));
            return false;
        }
        try {
            return executeOnce(draftId);
        } finally {
            running.remove(draftId);
        }
    }

    private boolean executeOnce(long draftId) {
        Optional<Job> job = readTx.execute(s -> requests.findById(draftId)
                .filter(StrDraftRequest::isRequested)
                .map(r -> new Job(r.getCaseId(), r.getCreatedBy(), r.getRequestSnapshot().deepCopy())));
        if (job == null || job.isEmpty()) {
            log.info("초안 요청이 REQUESTED 가 아니라 건너뜀 draft_no={}", DisplayNumber.draftNo(draftId));
            return false;
        }
        Job j = job.get();

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(asRequester(j.requesterId()));
        try {
            AiDtos.StrDraftResponse response = aiClient.createDraft(body(j));
            long caseVersion = readTx.execute(s -> cases.findById(j.caseId())
                    .orElseThrow(() -> new CaseNotFoundException(j.caseId())).getVersion());
            saveStrDraft.execute(j.caseId(), draftId, caseVersion, response.raw());
            log.info("초안 생성 끝 draft_no={}", DisplayNumber.draftNo(draftId));
            return true;
        } catch (AiClientException e) {
            if (AI_DRAFT_IN_PROGRESS.equals(e.code())) {
                log.info("AI 가 같은 초안을 아직 만드는 중 draft_no={}", DisplayNumber.draftNo(draftId));
                throw e;                                     // 실패로 남기지 않는다 — 큐는 잠시 뒤 다시
            }
            fail(draftId, e);
            throw e;
        } catch (RuntimeException e) {
            fail(draftId, e);
            throw e;
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * REQUESTED 면 FAILED 로. 재시도를 다 쓴 큐(QUEUE_GIVE_UP)·실행기 거절(NOT_STARTED)도 여기로 — REQUESTED 로 남으면
     * 같은 케이스의 다음 S1 이 오래된 요청 기준이 지날 때까지 409 DRAFT_IN_PROGRESS 를 받는다.
     * @return FAILED 로 바꿨으면 true
     */
    public boolean fail(long draftId, String reason) {
        Boolean done = writeTx.execute(s -> requests.findById(draftId)
                .filter(StrDraftRequest::isRequested)
                .map(r -> {
                    r.fail(Instant.now());
                    requests.save(r);
                    return true;
                }).orElse(false));
        if (Boolean.TRUE.equals(done)) {
            log.warn("초안 요청 FAILED draft_no={} reason={}", DisplayNumber.draftNo(draftId), reason);
        }
        return Boolean.TRUE.equals(done);
    }

    private void fail(long draftId, RuntimeException cause) {
        String reason = cause instanceof AiClientException ai ? ai.code() : cause.getClass().getSimpleName();
        try {
            fail(draftId, reason);
        } catch (RuntimeException e) {
            log.warn("초안 요청을 FAILED 로 바꾸지 못함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    e.getClass().getSimpleName());
        }
    }

    private AiDtos.StrDraftRequest body(Job j) {
        try {
            return om.treeToValue(j.snapshot(), AiDtos.StrDraftRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 초안 요청 본문을 읽지 못했다", e);
        }
    }

    /**
     * 요청한 조사역으로 실행 — CurrentUser.require()·감사 칸(updated_by)이 이 값을 본다. 역할은 초안을 요청할 수 있는 조사역
     * (S1 은 담당 조사역만 통과하므로 created_by 는 늘 조사역). 이름은 로그·화면에 쓰이지 않는다.
     */
    private static SecurityContext asRequester(Long userId) {
        AppUserDetails me = new AppUserDetails(userId, "str-draft-worker", "str-draft-worker", Role.INVESTIGATOR.name());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
        return ctx;
    }

    private record Job(long caseId, Long requesterId, com.fasterxml.jackson.databind.JsonNode snapshot) {}
}
