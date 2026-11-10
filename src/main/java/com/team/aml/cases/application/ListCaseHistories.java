package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 상태·업무 이력 전체 (CASES 명세 API-11, 계약서 C6). 조회 3역할, 최신이 먼저(id DESC). 상세의 최근 20건과 같은 모양.
 * NOTE 는 넣지 않는다 — 메모 전문은 C8(ListCaseNoteHistories). action 을 주면 그 동작만(NOTE 를 주면 400, 컨트롤러).
 */
@Service
public class ListCaseHistories {

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final UserRefs userRefs;

    public ListCaseHistories(CaseRepository cases, CaseHistoryRepository histories, UserRefs userRefs) {
        this.cases = cases;
        this.histories = histories;
        this.userRefs = userRefs;
    }

    @Transactional(readOnly = true)
    public Page<GetCaseDetail.History> execute(String caseNo, CaseHistoryAction action, int page, int size) {
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        PageRequest pageable = PageRequest.of(page, size);
        Page<CaseHistory> rows = action == null
                ? histories.findByCaseIdAndActionNotOrderByIdDesc(c.getId(), CaseHistoryAction.NOTE, pageable)
                : histories.findByCaseIdAndActionOrderByIdDesc(c.getId(), action, pageable);
        Map<Long, UserRef> refs = userRefs.of(rows.getContent().stream().map(CaseHistory::getCreatedBy).toList());
        return new PageImpl<>(rows.getContent().stream().map(h -> {
            UserRef by = refs.get(h.getCreatedBy());
            return new GetCaseDetail.History(h.getAction(), h.getFromStatus(), h.getToStatus(), h.getReasonCode(),
                    h.getComment(), by == null ? null : by.loginId(), h.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
        }).toList(), pageable, rows.getTotalElements());
    }
}
