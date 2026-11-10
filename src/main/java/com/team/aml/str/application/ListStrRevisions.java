package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.RevisionNotFoundException;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrRevision;
import com.team.aml.str.domain.StrSection;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * STR 수정 이력 (계약서 S4). 목록은 누가·언제·어느 단락을 고쳤는지, revision_no 를 주면 그 수정본의 전·후 본문.
 * 담당 조사역·승인자만 — 그 밖은 403 NOT_ASSIGNEE(S2 와 달리 제한 응답 없음). 본문 열람이라 STR_VIEW 한 줄(권한 확인 뒤).
 */
@Service
public class ListStrRevisions {

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final AccessLogWriter accessLog;

    public ListStrRevisions(StrDraftRepository drafts, CaseRepository cases, UserRepository users,
                            CurrentUser currentUser, AccessLogWriter accessLog) {
        this.drafts = drafts;
        this.cases = cases;
        this.users = users;
        this.currentUser = currentUser;
        this.accessLog = accessLog;
    }

    /** readOnly 아님 — strView 가 쓰기 트랜잭션(MANDATORY)을 요구한다. */
    @Transactional
    public Revisions list(String draftNo) {
        StrDraft draft = readable(draftNo);
        Map<Long, String> names = new HashMap<>();
        List<Item> items = draft.getRevisions().stream()
                .map(r -> item(r, names.computeIfAbsent(r.getRevisedBy(), this::nameOf)))
                .toList();
        return new Revisions(DisplayNumber.draftNo(draft.getId()), draft.getCurrentRevision().getRevisionNo(), items);
    }

    @Transactional
    public Detail one(String draftNo, int revisionNo) {
        StrDraft draft = readable(draftNo);
        StrRevision r = draft.getRevisions().stream()
                .filter(x -> x.getRevisionNo() == revisionNo)
                .findFirst()
                .orElseThrow(() -> new RevisionNotFoundException(draftNo, revisionNo));
        return new Detail(DisplayNumber.draftNo(draft.getId()), item(r, nameOf(r.getRevisedBy())),
                r.getBefore(), r.getAfter());
    }

    /** 404 → 권한 403 → 열람 기록. 404·403 은 기록하지 않는다(11/2·11/3 결정). */
    private StrDraft readable(String draftNo) {
        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        AppUserDetails me = currentUser.require();
        Role role = Role.valueOf(me.role());
        boolean allowed = role == Role.APPROVER
                || (role == Role.INVESTIGATOR && Objects.equals(c.getAssigneeId(), me.userId()));
        if (!allowed) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        accessLog.strView(draftId);
        return draft;
    }

    private String nameOf(Long userId) {
        return users.findById(userId).map(User::getDisplayName).orElse(null);
    }

    private static Item item(StrRevision r, String revisedByName) {
        return new Item(r.getRevisionNo(), r.getEditedSections(), revisedByName, r.getCreatedAt(), r.isAiOriginal());
    }

    public record Item(int revisionNo, List<StrSection> changedSections, String revisedBy, Instant revisedAt,
                       boolean aiOriginal) {}

    public record Revisions(String draftNo, int currentRevisionNo, List<Item> revisions) {}

    public record Detail(String draftNo, Item revision, List<NarrativeSection> before, List<NarrativeSection> after) {}
}
