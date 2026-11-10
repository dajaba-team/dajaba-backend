package com.team.aml.str.application;

import com.team.aml.cases.application.TouchCaseForRevision;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrRevision;
import com.team.aml.str.domain.StrSection;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * STR 본문 수정 (계약서 S3). 고친 단락의 문장만 받아 수정본 한 행을 추가한다 — cites·evidence_ids 는 지금 본문 그대로.
 * 문장 형식(빈 값·5,000자·NFC)은 api 가 먼저 400 으로 막는다. 여기 도메인 검사는 안전장치.
 *
 * <p>순서 (str.md "잠근 뒤 재확인", 미결 20):
 * 초안 읽기(404) → cases.TouchCaseForRevision(케이스 잠금 → version 409 → 담당 403 → 상태·현재 초안 409, 케이스 version +1)
 * → StrDraft.revise(사용 중이 아니면 409 DRAFT_LOCKED) → 저장(수정본 INSERT, 초안 version +1).
 * 케이스를 먼저 잠그므로 같은 케이스의 수정·승인 요청이 동시에 와도 한쪽은 409 CONCURRENT_UPDATE.
 */
@Service
public class ReviseStrDraft {

    private final StrDraftRepository drafts;
    private final TouchCaseForRevision touchCase;
    private final CurrentUser currentUser;
    private final UserRepository users;

    public ReviseStrDraft(StrDraftRepository drafts, TouchCaseForRevision touchCase, CurrentUser currentUser,
                          UserRepository users) {
        this.drafts = drafts;
        this.touchCase = touchCase;
        this.currentUser = currentUser;
        this.users = users;
    }

    @Transactional
    public Revised execute(String draftNo, long expectedCaseVersion, Map<StrSection, String> editedTexts) {
        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        long caseVersion = touchCase.execute(draft.getCaseId(), draftId, expectedCaseVersion);
        long actorId = currentUser.require().userId();
        StrRevision revision = draft.revise(editedTexts, actorId);
        drafts.save(draft);                                   // flush — 수정본 created_at 이 채워진다
        return new Revised(DisplayNumber.draftNo(draftId), revision.getRevisionNo(), revision.getEditedSections(),
                users.findById(actorId).map(User::getDisplayName).orElse(null), revision.getCreatedAt(),
                revision.getAfter(),
                draft.getVersion(), caseVersion);
    }

    /**
     * 저장 결과. narrative = 저장된 본문 전체(S2 와 같은 모양) — 화면이 S2 를 다시 부르지 않고 저장을 확인한다 (#91 리뷰).
     * 본문을 고쳤으니 예전 검증은 지금 본문과 맞지 않는다 — 화면은 needs_verification 으로 [승인 요청]을 끈다.
     */
    public record Revised(String draftNo, int revisionNo, List<StrSection> changedSections,
                          String editedBy, Instant editedAt, List<NarrativeSection> narrative,
                          long version, long caseVersion) {
        public boolean needsVerification() { return true; }
    }
}
