package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import org.springframework.stereotype.Component;

/**
 * 지금 조사 메모 = 이 케이스의 마지막 NOTE 행 comment (안건 16 A안). 메모를 쓴 적이 없으면 null.
 * 메모 저장(API-05)과 상세(API-02 investigator_notes)가 같은 값을 쓰도록 여기 한 곳에서 읽는다.
 */
@Component
class CaseNotes {

    private final CaseHistoryRepository histories;

    CaseNotes(CaseHistoryRepository histories) {
        this.histories = histories;
    }

    String currentOf(long caseId) {
        return histories.findFirstByCaseIdAndActionOrderByIdDesc(caseId, CaseHistoryAction.NOTE)
                .map(CaseHistory::getComment).orElse(null);
    }
}
