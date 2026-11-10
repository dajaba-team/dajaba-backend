package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조사 메모 저장 (CASES 명세 API-05, 계약서 A3). 담당 조사역만, 편집 가능 상태(REVIEWING·ESCALATED·DRAFTED·보완 반려)만.
 * 검사 순서: 케이스 404 → version 409 CONCURRENT_UPDATE(내용이 같아도) → 담당자 403 NOT_ASSIGNEE
 * → 상태 409 INVALID_CASE_TRANSITION(details.action = UPDATE_NOTES) → 같은 내용이면 no-op(version 그대로).
 * 바뀌면 NOTE 이력(메모 전문) + cases.version +1 이 한 트랜잭션. 길이(0~10,000자)·null 은 컨트롤러가 400.
 * 동시에 같은 version 으로 두 요청이 오면 bumpVersion 의 version 검사(와 NOTE (case_id, case_version_before) UNIQUE)로 뒤가 409.
 */
@Service
public class UpdateCaseNotes {

    private final CaseChanges changes;
    private final CaseNotes notes;
    private final CurrentUser currentUser;

    public UpdateCaseNotes(CaseChanges changes, CaseNotes notes, CurrentUser currentUser) {
        this.changes = changes;
        this.notes = notes;
        this.currentUser = currentUser;
    }

    @Transactional
    public CaseChangeResult execute(String caseNo, long expectedVersion, String investigatorNotes) {
        long actorId = currentUser.require().userId();
        Case c = changes.load(caseNo, expectedVersion);
        changes.requireAssignee(c, actorId);
        if (!c.updateNotes(investigatorNotes, notes.currentOf(c.getId()), actorId)) {
            return changes.result(c);
        }
        return changes.bumpAndRecord(c);
    }
}
