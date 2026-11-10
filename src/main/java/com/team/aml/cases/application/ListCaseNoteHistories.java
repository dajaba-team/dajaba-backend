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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 메모 전문 이력 (CASES 명세 API-15, 계약서 C8). 조회 3역할, 최신이 먼저(id DESC).
 * after_text = 그 NOTE 행의 전문, before_text = 바로 앞 NOTE 행의 전문 — 페이지 밖에 있어도 찾아서 채운다. 첫 기록은 null.
 * 비운 기록은 after_text = "". 상세처럼 열람 기록은 남기지 않는다(C7 과 같음 — 케이스 열람은 상세가 남긴다).
 */
@Service
public class ListCaseNoteHistories {

    /** 메모 이력 한 줄. createdBy 는 login_id. */
    public record NoteHistory(String beforeText, String afterText, long caseVersionBefore, String createdBy,
                              Instant createdAt) {
    }

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final UserRefs userRefs;

    public ListCaseNoteHistories(CaseRepository cases, CaseHistoryRepository histories, UserRefs userRefs) {
        this.cases = cases;
        this.histories = histories;
        this.userRefs = userRefs;
    }

    @Transactional(readOnly = true)
    public Page<NoteHistory> execute(String caseNo, int page, int size) {
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        Page<CaseHistory> rows = histories.findByCaseIdAndActionOrderByIdDesc(
                c.getId(), CaseHistoryAction.NOTE, PageRequest.of(page, size));
        List<CaseHistory> content = rows.getContent();
        if (content.isEmpty()) {
            return new PageImpl<>(List.of(), rows.getPageable(), rows.getTotalElements());
        }

        String olderThanPage = histories.findFirstByCaseIdAndActionAndIdLessThanOrderByIdDesc(
                        c.getId(), CaseHistoryAction.NOTE, content.get(content.size() - 1).getId())
                .map(CaseHistory::getComment).orElse(null);
        Map<Long, UserRef> refs = userRefs.of(content.stream().map(CaseHistory::getCreatedBy).toList());

        List<NoteHistory> items = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            CaseHistory h = content.get(i);
            String before = i + 1 < content.size() ? content.get(i + 1).getComment() : olderThanPage;
            UserRef by = refs.get(h.getCreatedBy());
            items.add(new NoteHistory(before, h.getComment(), h.getCaseVersionBefore(),
                    by == null ? null : by.loginId(), h.getCreatedAt().truncatedTo(ChronoUnit.SECONDS)));
        }
        return new PageImpl<>(items, rows.getPageable(), rows.getTotalElements());
    }
}
