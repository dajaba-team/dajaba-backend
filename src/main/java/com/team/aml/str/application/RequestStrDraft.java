package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.cases.domain.*;
import com.team.aml.detection.application.GetAlertForDraft;
import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftInProgressException;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * STR 초안 생성·재생성 (계약서 S1, flows/03_str, 레퍼런스 8.4). "DB → AI → DB" — <b>이 클래스에는 트랜잭션이 없다.</b>
 * AI 를 기다리는 동안(≤ 90초) DB 트랜잭션·락을 잡지 않는다 (RefreshCaseSummary 와 같은 모양).
 *
 * <ol>
 *   <li>요청 기록 트랜잭션(짧게): 케이스 404 → version 409 → 담당 403 → 상태 409(GENERATE_DRAFT 가 안 되는 상태)
 *       → 진행 중 요청이 있으면 409 DRAFT_IN_PROGRESS (단, AI 예산 + 여유보다 오래된 요청은 서버가 꺼져 남은 것이라 EXPIRED 로 닫고 진행)
 *       → draft_id 발급(시퀀스) → AI 요청 본문을 만들어 요청 기록 REQUESTED 로 저장.</li>
 *   <li>트랜잭션 밖: AiClient.createDraft (규약 §5.1, draft_id = DRF-%06d).</li>
 *   <li>SaveStrDraft(한 트랜잭션): 케이스 잠금 → 응답 확인 → 초안·수정본 1번 저장 → 케이스 DRAFTED → 요청 SUCCEEDED.</li>
 *   <li>2·3에서 실패하면 요청 기록을 별도 트랜잭션으로 FAILED — 케이스는 그대로(ESCALATED 등). 원래 예외를 그대로 올린다.</li>
 * </ol>
 */
@Service
public class RequestStrDraft {
    /** 상태가 맞지 않을 때 details.action. */
    static final String ACTION = "GENERATE_DRAFT";
    /** AI 예산(aml.ai.timeout-seconds) 뒤 SaveStrDraft·FAILED 기록까지 끝내는 여유. */
    static final Duration STALE_MARGIN = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(RequestStrDraft.class);

    private final CaseRepository cases;
    private final GetAlertForDraft alertForDraft;
    private final StrDraftRequestRepository requests;
    private final AiClient aiClient;
    private final SaveStrDraft saveStrDraft;
    private final CurrentUser currentUser;
    private final ObjectMapper om;
    private final TransactionTemplate tx;
    /** 이보다 오래된 REQUESTED 는 살아 있는 요청이 아니다 — 서버가 AI 를 기다리다 꺼진 흔적 (#77 리뷰). */
    private final Duration staleAfter;

    public RequestStrDraft(CaseRepository cases, GetAlertForDraft alertForDraft, StrDraftRequestRepository requests,
                           AiClient aiClient, SaveStrDraft saveStrDraft, CurrentUser currentUser, ObjectMapper om,
                           PlatformTransactionManager transactionManager, AmlProperties props){
        this.cases = cases;
        this.alertForDraft = alertForDraft;
        this.requests = requests;
        this.aiClient = aiClient;
        this.saveStrDraft = saveStrDraft;
        this.currentUser = currentUser;
        this.om = om;
        this.tx = new TransactionTemplate(transactionManager);
        this.staleAfter = Duration.ofSeconds(props.ai().timeoutSeconds()).plus(STALE_MARGIN);
    }

    /**
     * @param expectedCaseVersion 화면이 읽은 cases.version. 저장할 때(SaveStrDraft)도 같아야 한다
     * @return 저장한 초안 번호와 새 cases.version — 201 응답
     */
    public Created execute(String caseNo, long expectedCaseVersion){
        long actorId = currentUser.require().userId();

        Started started = start(caseNo, expectedCaseVersion,actorId);

        try{
            AiDtos.StrDraftResponse response = aiClient.createDraft(started.body());
            SaveStrDraft.Saved saved = saveStrDraft.execute(started.caseId(), started.draftId(),
                    expectedCaseVersion, response.raw());
            return new Created(caseNo, "DRAFTED", saved.caseVersion(), DisplayNumber.draftNo(saved.draftId()));
        }catch (RuntimeException e){
            markFailed(started.draftId());
            throw e;
        }
    }

    private Started start(String caseNo, long expectedCaseVersion, long actorId) {
        try {
            return tx.execute(s -> {
                Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
                check(c, expectedCaseVersion, actorId);
                expireStaleOrThrow(c);
                long draftId = requests.nextDraftId();
                AiDtos.StrDraftRequest body = bodyFor(c, draftId);
                requests.save(StrDraftRequest.request(draftId, c.getId(), om.valueToTree(body)));
                return new Started(c.getId(), draftId, body);
            });
        } catch (DataIntegrityViolationException e) {
            // 두 요청이 동시에 위 검사를 통과하면 V008 partial unique(케이스당 REQUESTED 하나)가 뒤 INSERT 를 막는다
            throw new DraftInProgressException(caseNo);
        }
    }

    /**
     * 진행 중 요청이 없으면 그냥 지나간다. 있는데 staleAfter 보다 오래됐으면 EXPIRED 로 닫고 지나가고, 아니면 409.
     * saveAndFlush — Hibernate 는 커밋 때 INSERT 를 UPDATE 보다 먼저 보내서, flush 안 하면 새 REQUESTED INSERT 가
     * 아직 REQUESTED 인 옛 행과 V008 partial unique 에 걸린다.
     */
    private void expireStaleOrThrow(Case c) {
        StrDraftRequest running = requests.findRequestedByCaseId(c.getId()).orElse(null);
        if (running == null) return;
        Instant now = Instant.now();
        if (running.getCreatedAt().isAfter(now.minus(staleAfter))) {
            throw new DraftInProgressException(c.getCaseNo());
        }
        log.warn("오래된 초안 요청을 EXPIRED 로 닫음 draft_id={} case_no={} created_at={}",
                running.getId(), c.getCaseNo(), running.getCreatedAt());
        running.expire(now);
        requests.saveAndFlush(running);
    }

    /** 검사 순서는 AttachDraftToCase·RefreshCaseSummary 와 같다. str 은 Case 의 get·is 만 부른다(ArchUnit). */
    private static void check(Case c, long expectedVersion, long actorId) {
        if (c.getVersion() != expectedVersion) {
            throw new OptimisticLockException("케이스가 그 사이 바뀌었습니다: " + c.getCaseNo()
                    + " (expected " + expectedVersion + ", current " + c.getVersion() + ")");
        }
        if (c.getAssigneeId() == null || c.getAssigneeId() != actorId) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        if (!c.getStatus().allows(CaseAction.GENERATE_DRAFT, c.getRejectionReasonCode())) {
            throw InvalidCaseTransitionException.notEditable(c.getStatus(), c.getRejectionReasonCode(), ACTION);
        }
    }

    /**
     * 규약 v0.4 §5.1 요청. alert·reasons·prior_alert_count 는 detection GetAlertForDraft 그대로(요약과 같은 값).
     * evidence_transaction_ids = 경보 거래 전체(중복 제거, 오름차순). 점수가 없으면 reasons []. 목업 DB 값은 넣지 않는다(11/6).
     * investigator_notes 는 지윤 님 메모(A3)가 들어오면 채운다 — 지금은 null. reporting_institution 도 null(AI 가 거래자 계좌 은행명).
     */
    AiDtos.StrDraftRequest bodyFor(Case c, long draftId) {
        AlertForDraft a = alertForDraft.execute(c.getAlertId());
        List<Long> evidence = a.alert().transactionIds().stream().distinct().sorted().toList();
        JsonNode reasons = a.reasons() != null ? a.reasons() : om.createArrayNode();
        return new AiDtos.StrDraftRequest(DisplayNumber.draftNo(draftId), c.getCaseNo(), a.alert(), evidence,
                reasons, a.priorAlertCount(), null, null);
    }

    /** 원래 예외를 덮지 않는다 — FAILED 로 못 바꿔도 로그만 남긴다. 이미 끝난 요청(SUCCEEDED·EXPIRED)은 그대로. */
    private void markFailed(long draftId) {
        try {
            tx.executeWithoutResult(s -> requests.findById(draftId)
                    .filter(StrDraftRequest::isRequested)
                    .ifPresent(r -> {
                        r.fail(Instant.now());
                        requests.save(r);
                    }));
        } catch (RuntimeException e) {
            log.warn("초안 요청 기록을 FAILED 로 바꾸지 못함 draft_id={}", draftId, e);
        }
    }

    /**
     * @param caseStatus  저장 뒤 케이스 상태 — 늘 DRAFTED (AttachDraftToCase)
     * @param caseVersion 연결 뒤 cases.version (SCRUM-141)
     * @param draftNo     DRF-%06d
     */
    public record Created(String caseNo, String caseStatus, long caseVersion, String draftNo) {}

    private record Started(long caseId, long draftId, AiDtos.StrDraftRequest body) {}
}
