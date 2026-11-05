package com.team.aml.cases.application;

/**
 * 케이스 생성 배치 한 번의 결과.
 *
 * @param candidates     점수 있고 케이스 없는 경보 수 (batch_runs.rows_in)
 * @param opened         새로 만든 케이스 수 (batch_runs.rows_out)
 * @param notHigh        CasePolicy 가 HIGH 가 아니라고 본 경보 — 케이스를 만들지 않음
 * @param alreadyOpened  그 사이 다른 실행이 먼저 만든 경보(409 CASE_ALREADY_OPENED·alert_id 중복) — 건너뜀
 * @param failed         그 밖의 이유로 실패한 경보. 그 경보만 롤백되고 나머지는 계속한다
 */
public record OpenCasesSummary(int candidates, int opened, int notHigh, int alreadyOpened, int failed) {

    public boolean hasFailures() {
        return failed > 0;
    }

    /** batch_runs.error_message 에 남길 한 줄. 경보 내용 없이 건수만. */
    public String describe() {
        return "대상 " + candidates + " · 생성 " + opened + " · HIGH 아님 " + notHigh
                + " · 이미 있음 " + alreadyOpened + " · 실패 " + failed;
    }
}
