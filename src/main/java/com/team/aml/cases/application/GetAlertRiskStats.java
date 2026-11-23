package com.team.aml.cases.application;

import com.team.aml.cases.domain.CasePolicy;
import com.team.aml.cases.domain.CasePolicySource;
import com.team.aml.cases.domain.PolicyNotConfiguredException;
import com.team.aml.detection.application.CountAlertScores;
import com.team.aml.detection.domain.AlertScoreStats;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 관리자 대시보드 경보 위험 등급 분포 (SCRUM-360). 등급은 케이스를 만들 때와 같은 운영 정책(CasePolicy)으로 나눠요 —
 * 그래야 HIGH 수가 케이스 전환 수와 맞아요. 정책이 아직 없으면 등급만 빼고 건수는 그대로(정책 없음은 오류가 아님).
 */
@Service
public class GetAlertRiskStats {

    /** api 가 detection 타입을 모르게 숫자로 풀어 둔다(ArchitectureTest). high·medium·low 는 정책이 없으면 null. */
    public record Result(LocalDate date, long total, long scored, Long high, Long medium, Long low,
                         long unscored, long failed, long caseOpened, String policyVersion) {

        static Result of(LocalDate date, AlertScoreStats s, String policyVersion) {
            return new Result(date, s.total(), s.scored(), s.high(), s.medium(), s.low(), s.unscored(), s.failed(),
                    s.caseOpened(), policyVersion);
        }
    }

    private final CasePolicySource policies;
    private final CountAlertScores counts;

    public GetAlertRiskStats(CasePolicySource policies, CountAlertScores counts) {
        this.policies = policies;
        this.counts = counts;
    }

    public Result execute(LocalDate date) {
        CasePolicy policy;
        try {
            policy = policies.current();
        } catch (PolicyNotConfiguredException e) {
            return Result.of(date, counts.count(date, null, null), null);
        }
        return Result.of(date, counts.count(date, policy.getLowToMedium(), policy.getMediumToHigh()),
                policy.getPolicyVersion());
    }
}
