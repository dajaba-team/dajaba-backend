package com.team.aml.shared.domain;

import java.util.Map;

/**
 * 409 DRAFT_SUPERSEDED — 요청한 초안이 재생성으로 대체돼 케이스의 현재 초안이 다른 초안이다 (계약서 3절).
 * details: draft_no(요청한 초안), current_draft_no(지금 초안). 화면은 current_draft_no 로 초안을 다시 불러온다.
 * 번호는 화면 번호(DRF-000123) — DB id 는 밖으로 안 낸다 (계약서 2.2).
 * cases 가 str 을 참조할 수 없어서 shared 에 둔다 (SCRUM-223). 현재 초안이 없을 때는 쓰지 않는다(INVALID_CASE_TRANSITION).
 */
public class DraftSupersededException extends DomainException {

    public DraftSupersededException(long draftId, long currentDraftId) {
        super("대체된 초안입니다: " + DisplayNumber.draftNo(draftId)
                        + " (현재 초안 " + DisplayNumber.draftNo(currentDraftId) + ")",
                Map.of("draft_no", DisplayNumber.draftNo(draftId),
                        "current_draft_no", DisplayNumber.draftNo(currentDraftId)));
    }
}
