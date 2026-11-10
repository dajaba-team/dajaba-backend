package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.VerificationResult;
import com.team.aml.str.domain.VerificationResultRepository;
import com.team.aml.str.domain.VerificationStatus;
import com.team.aml.str.domain.VerifyInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STR 검증 (계약서 S5, body {}). 지금 저장된 본문을 AI 로 검사하고 결과를 한 줄 남긴다 — "DB → AI → DB" (RequestStrDraft 와 같은 틀).
 * 클래스에 @Transactional 이 없다: AI 를 기다리는 동안(최대 90초) DB 트랜잭션·락을 잡지 않는다.
 *
 * <ol>
 *   <li>짧은 읽기 트랜잭션: 초안(404) → 케이스(404) → 담당(403) → 사용 중 초안(409 DRAFT_LOCKED)
 *       → DRAFTED·보완 요청 반려(409 INVALID_CASE_TRANSITION). 본문·수정본 번호·generation_id 를 이때 고정한다.</li>
 *   <li>같은 초안 검증 중이면 409 VERIFY_IN_PROGRESS. 트랜잭션 밖에서 aiClient.verify (cites 포함, 규약 v0.4).
 *       보내는 근거는 CurrentNarrative — S2 화면과 같은 값.</li>
 *   <li>새 트랜잭션에 verification_results 한 줄. VERIFY_UNAVAILABLE(AI 200)도 저장 — 자동 재시도 없음.
 *       AI 4xx·5xx·타임아웃은 AiClientException(502) 그대로, 아무것도 저장하지 않는다.</li>
 * </ol>
 * 3에서 수정본 번호를 다시 읽지 않는다 — 검증 중 누가 고쳤다면 예전 본문 기준 결과로 남아야 승인 요청 게이트(해시 비교)가 막는다.
 */
@Service
public class VerifyStrDraft {

    private static final Logger log = LoggerFactory.getLogger(VerifyStrDraft.class);

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final VerificationResultRepository results;
    private final AiClient aiClient;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final CurrentNarrative currentNarrative;

    /** 지금 AI 를 기다리는 초안 id. 백엔드 한 대(MVP) 기준 — 여러 대로 늘리면 DB 기록으로 바꾼다. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public VerifyStrDraft(StrDraftRepository drafts, CaseRepository cases, VerificationResultRepository results,
                          AiClient aiClient, CurrentUser currentUser, PlatformTransactionManager transactionManager,
                          CurrentNarrative currentNarrative) {
        this.drafts = drafts;
        this.cases = cases;
        this.results = results;
        this.aiClient = aiClient;
        this.currentUser = currentUser;
        this.tx = new TransactionTemplate(transactionManager);
        this.currentNarrative = currentNarrative;
    }

    /** @return AI 검증 응답 원문(raw) — 화면 검증 패널이 그대로 그린다. */
    public JsonNode execute(String draftNo) {
        long actorId = currentUser.require().userId();
        long draftId = DraftNumbers.require(draftNo);
        Snapshot s = Objects.requireNonNull(tx.execute(st -> snapshot(draftNo, draftId, actorId)));
        if (!inFlight.add(draftId)) {
            throw new VerifyInProgressException(draftNo);
        }
        try {
            AiDtos.VerifyResponse response = aiClient.verify(draftNo, s.request());
            VerificationResult result = toResult(draftId, s, response);
            tx.executeWithoutResult(st -> results.save(result));
            return response.raw();
        } finally {
            inFlight.remove(draftId);
        }
    }

    private Snapshot snapshot(String draftNo, long draftId, long actorId) {
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        if (c.getAssigneeId() == null || c.getAssigneeId() != actorId) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        draft.requireActive();
        if (c.getStatus() != CaseStatus.DRAFTED && !c.isRejectedForRevision()) {
            throw InvalidCaseTransitionException.notEditable(c.getStatus(), c.getRejectionReasonCode(), "VERIFY_DRAFT");
        }
        List<NarrativeSection> body = currentNarrative.of(draft);   // 근거는 같은 해시 최신 검증의 narrative_refs 우선
        int revisionNo = draft.getCurrentRevision().getRevisionNo();
        List<AiDtos.NarrativeSectionDto> narrative = body.stream()
                .map(n -> new AiDtos.NarrativeSectionDto(n.section().name(), n.text(), n.cites(), n.evidenceIds()))
                .toList();
        return new Snapshot(draft.getGenerationId(), revisionNo, NarrativeHash.of(body),
                new AiDtos.VerifyRequest(draft.getGenerationId(), revisionNo, narrative));
    }

    /**
     * 보낸 generation_id·수정본 번호로 저장한다(검사를 맡긴 대상). 해시는 AI 가 계산한 값 — 우리 계산과 다르면 경고만 남긴다
     * (게이트가 지금 본문 해시와 비교하므로 다르면 승인 요청이 막힌다 = 안전한 쪽).
     */
    private static VerificationResult toResult(long draftId, Snapshot s, AiDtos.VerifyResponse r) {
        if (!s.narrativeSha256().equals(r.narrativeSha256())) {
            log.warn("검증 해시가 다름 draft_id={} revision_no={} (백엔드 {}…, AI {}…)", draftId, s.revisionNo(),
                    s.narrativeSha256().substring(0, 8), r.narrativeSha256() == null ? null
                            : r.narrativeSha256().substring(0, Math.min(8, r.narrativeSha256().length())));
        }
        try {
            return VerificationResult.record(draftId, r.verificationId(), s.generationId(), s.revisionNo(),
                    r.narrativeSha256(), VerificationStatus.valueOf(r.status()), r.verifierVersion(),
                    Instant.parse(r.producedAt()), r.raw());
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 검증 응답 값이 올바르지 않습니다");
        }
    }

    private record Snapshot(String generationId, int revisionNo, String narrativeSha256,
                            AiDtos.VerifyRequest request) {}
}
