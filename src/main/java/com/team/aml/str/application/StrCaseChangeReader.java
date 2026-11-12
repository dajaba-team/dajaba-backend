package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;

/**
 * 케이스를 바꾼 뒤 공통 변경 응답(StrCaseChange)을 만든다. Case 는 읽기(get*)만 — 바꾸는 건 cases 공개 유스케이스 몫
 * (02_structure "다른 기능의 domain 엔티티를 읽기로 참조"는 허용, GetStrDraft 와 같음).
 *
 * <p>케이스를 바꾼 <b>같은 트랜잭션 안에서</b> 부른다. cases 유스케이스가 saveAndFlush 한 엔티티가 영속성 컨텍스트에 남아 있어
 * findById 가 DB 를 다시 읽지 않고 새 version·updated_at 을 그대로 준다 (SCRUM-141 과 같은 이유).
 */
@Component
class StrCaseChangeReader {

    private final CaseRepository cases;
    private final UserRepository users;

    StrCaseChangeReader(CaseRepository cases, UserRepository users) {
        this.cases = cases;
        this.users = users;
    }

    StrCaseChange read(long caseId) {
        Case c = cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        StrCaseChange.Assignee assignee = c.getAssigneeId() == null ? null : users.findById(c.getAssigneeId())
                .map(u -> new StrCaseChange.Assignee(u.getLoginId(), u.getDisplayName()))
                .orElse(null);
        return new StrCaseChange(
                c.getCaseNo(),
                c.getStatus().name(),
                assignee,
                c.getRejectionReasonCode() == null ? null : c.getRejectionReasonCode().name(),
                c.getCurrentDraftId() == null ? null : DisplayNumber.draftNo(c.getCurrentDraftId()),
                c.getVersion(),
                c.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS));   // 시스템 시각은 초 단위 (계약서 2.4)
    }
}
