package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseSummaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 AI 조사 요약 (CASES 명세 API-13, 계약서 C7). 조회 3역할. 요약이 없으면 summary 는 null.
 * 현재 요약 = 케이스별 최신 1건. is_current 는 <b>지금 입력으로 다시 만든 해시</b>와 비교한다
 * (케이스 version 차이로 판정하지 않는다 — 메모·상태만 바뀌면 그대로 true).
 * 상세(API-02)의 latest_summary 도 {@link #latestOf(Case)} 로 같은 값을 준다.
 * 열람 기록은 남기지 않는다 — 케이스 열람 기록은 상세(API-02)가 남긴다.
 */
@Service
public class GetCaseSummary {

    /** @param summary 요약이 아직 없으면 null */
    public record Result(String caseNo, CaseSummaryResult.Summary summary) {}

    private final CaseRepository cases;
    private final CaseSummaryRepository summaries;
    private final CaseSummaryInputs inputs;

    public GetCaseSummary(CaseRepository cases, CaseSummaryRepository summaries, CaseSummaryInputs inputs) {
        this.cases = cases;
        this.summaries = summaries;
        this.inputs = inputs;
    }

    /** @throws CaseNotFoundException 없는 케이스 (404) */
    @Transactional(readOnly = true)
    public Result execute(String caseNo) {
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        return new Result(c.getCaseNo(), latestOf(c));
    }

    /** 최신 요약과 is_current. 요약이 없으면 null — 그때는 입력을 만들지 않는다(경보 조회 생략). */
    CaseSummaryResult.Summary latestOf(Case c) {
        return summaries.findFirstByCaseIdOrderByCreatedAtDescIdDesc(c.getId())
                .map(s -> CaseSummaryResult.Summary.of(s, s.isCurrentFor(inputs.currentInput(c))))
                .orElse(null);
    }
}
