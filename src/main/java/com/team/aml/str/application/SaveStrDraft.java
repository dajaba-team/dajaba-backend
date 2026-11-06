package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.cases.application.AttachDraftToCase;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.str.domain.DraftRequestClosedException;
import com.team.aml.str.domain.GeneratedDraft;
import com.team.aml.str.domain.InvalidSectionTextException;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * AI 초안 응답을 저장하고 케이스에 붙인다 — 한 트랜잭션 (flows/03_str ⑧~⑪). AI 호출은 이 밖에서(RequestStrDraft) 끝난 뒤다.
 *
 * 순서 (바꾸지 않는다):
 *   케이스 잠금 — AttachDraftToCase.lock. 초안 INSERT(FK → cases KEY SHARE)보다 먼저 잡아야 동시 저장이 교착이 아니라 409 가 된다 (#43 리뷰 1번)
 *   요청 기록 확인 — 이 케이스의 REQUESTED 인 같은 draft_id. 끝난 요청(만료·실패)에 늦게 온 응답은 저장하지 않는다 (미결 21 ⑤)
 *   응답 읽기 — draft_id 가 보낸 것과 같은지, 단락·cites·evidence_ids
 *   재생성이면 예전 ACTIVE 초안 SUPERSEDED (케이스당 ACTIVE 하나 — 저장소가 UPDATE 를 먼저 내보낸다, #53 리뷰)
 *   새 초안 + 수정본 1번(AI 원문) INSERT
 *   AttachDraftToCase.execute — 케이스 DRAFTED, current_draft_id, 이력. 새 cases.version 을 받는다 (SCRUM-141)
 *   요청 기록 SUCCEEDED
 *
 * 어디서든 실패하면 전부 롤백 — 요청 기록은 REQUESTED 로 남고, FAILED 로 바꾸는 건 부른 쪽(RequestStrDraft)이 별도 트랜잭션으로 한다.
 */
@Service
public class SaveStrDraft {

    private static final Logger log = LoggerFactory.getLogger(SaveStrDraft.class);

    private final AttachDraftToCase attachDraft;
    private final StrDraftRepository drafts;
    private final StrDraftRequestRepository requests;

    public SaveStrDraft(AttachDraftToCase attachDraft, StrDraftRepository drafts, StrDraftRequestRepository requests) {
        this.attachDraft = attachDraft;
        this.drafts = drafts;
        this.requests = requests;
    }

    /**
     * @param caseId              초안을 붙일 케이스
     * @param draftId             AI 호출 전에 받은 번호(요청 기록의 draft_id)
     * @param expectedCaseVersion AI 호출 전에 읽은 cases.version. 그 사이 바뀌었으면 409
     * @param aiResponse          AI 초안 응답 전체(규약 5.2) — payload 로 그대로 저장한다
     */
    @Transactional
    public Saved execute(long caseId, long draftId, long expectedCaseVersion, JsonNode aiResponse) {
        attachDraft.lock(caseId, expectedCaseVersion);                                   // ①

        StrDraftRequest request = requests.findById(draftId)                            // ②
                .filter(r -> r.getCaseId() == caseId)
                // draftId 는 부른 쪽(RequestStrDraft)이 이 케이스로 만든 번호 — 여기 걸리면 백엔드 버그라 500 이 맞다
                .orElseThrow(() -> new IllegalStateException("이 케이스의 초안 요청 기록이 없다: draft_id " + draftId));
        if (!request.isRequested()) {                                                    // 만료·실패 뒤 늦게 온 응답 — 409
            throw new DraftRequestClosedException(request.getStatus());
        }

        GeneratedDraft generated = AiDraftResponseReader.read(aiResponse,              // ③
                request.getRequestSnapshot().path("draft_id").asText(null));

        drafts.findActiveByCaseId(caseId).ifPresent(StrDraft::supersede);             // ④

        StrDraft draft = drafts.save(newDraft(draftId, caseId, generated));              // ⑤

        long caseVersion = attachDraft.execute(caseId, draft.getId(), expectedCaseVersion);   // ⑥

        request.succeed(Instant.now());                                                   // ⑦
        requests.save(request);

        return new Saved(draft.getId(), caseVersion);
    }

    /**
     * 응답 읽기를 통과했어도 초안 규칙(generation_id·prompt_version·facts·단락 한 세트·문장 길이)에 안 맞으면 AI 응답 문제다
     * — 500(IllegalArgumentException)·409(InvalidSectionTextException)가 아니라 AI_BAD_RESPONSE 502 (#60 리뷰 1).
     * 응답에 실리는 메시지는 고정 문구, 무엇이 틀렸는지는 로그로만 (도메인 메시지는 단락 이름·개수뿐, 본문 글자 없음).
     */
    private static StrDraft newDraft(long draftId, long caseId, GeneratedDraft generated) {
        try {
            return StrDraft.create(draftId, caseId,
                    generated.generationId(), generated.generatorVersion(), generated.producedAt(),
                    generated.payload(), generated.narrative());
        } catch (IllegalArgumentException | InvalidSectionTextException e) {
            log.warn("AI 초안 응답이 초안 규칙에 맞지 않음 draft_id={}: {}", draftId, e.getMessage());
            AiClientException badResponse = new AiClientException("AI_BAD_RESPONSE", "AI 초안 응답이 초안 규칙에 맞지 않는다");
            badResponse.initCause(e);
            throw badResponse;
        }
    }

    /**
     * @param draftId     저장한 초안 번호 — 화면에는 DisplayNumber 로 DRF-%06d
     * @param caseVersion 연결 뒤 cases.version — 생성 응답의 case_version 에 그대로 싣는다
     */
    public record Saved(long draftId, long caseVersion) {}
}
