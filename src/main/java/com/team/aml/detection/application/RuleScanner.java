package com.team.aml.detection.application;

import com.team.aml.detection.domain.ActiveRule;
import com.team.aml.detection.domain.FanEvidence;
import com.team.aml.detection.domain.FanInRule;
import com.team.aml.detection.domain.FanMinute;
import com.team.aml.detection.domain.FanOutRule;
import com.team.aml.detection.domain.NewAlert;
import com.team.aml.detection.domain.RuleScanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 켜진 룰 전부를 한 트랜잭션으로 스캔한다 (SD 명세 §5). 하나라도 실패하면 그 실행의 경보가 전부 롤백 →
 * 다시 돌려도 두 번 생기지 않는다. alerts UNIQUE(rule_ref_id, account_key, window_start) 가 한 번 더 막는다.
 */
@Service
public class RuleScanner {

    private static final Logger log = LoggerFactory.getLogger(RuleScanner.class);

    /**
     * 임시 (SCRUM-260): V016 으로 다섯 룰을 구현 전에 먼저 켰다. 여기 없는 룰은 경고만 남기고 건너뛰며 alerts_by_rule 에도 넣지 않는다.
     * 룰 PR 마다 ID 를 여기와 switch 에 추가하고, 마지막 룰(R03, SCRUM-193) PR 에서 이 목록과 건너뛰기를 지운다.
     */
    private static final Set<String> IMPLEMENTED = Set.of(FanOutRule.RULE_ID, FanInRule.RULE_ID);

    private final RuleScanRepository scans;
    private final RuleScanRecorder recorder;

    public RuleScanner(RuleScanRepository scans, RuleScanRecorder recorder) {
        this.scans = scans;
        this.recorder = recorder;
    }

    @Transactional
    public List<RuleAlertCount> scan(long batchRunId, LocalDate date) {
        List<RuleAlertCount> counts = new ArrayList<>();
        for (ActiveRule rule : scans.findActiveRules()) {
            if (!IMPLEMENTED.contains(rule.ruleId())) {
                log.warn("rule scan skipped unimplemented rule rule_id={} version={}", rule.ruleId(), rule.version());
                continue;
            }
            int created = switch (rule.ruleId()) {
                case FanOutRule.RULE_ID -> scanFanOut(batchRunId, date, rule);
                case FanInRule.RULE_ID -> scanFanIn(batchRunId, date, rule);
                default -> throw new IllegalStateException("구현되지 않은 룰이 켜져 있습니다: " + rule.ruleId());
            };
            counts.add(new RuleAlertCount(rule.ruleId(), rule.version(), created));
        }
        int alerts = counts.stream().mapToInt(RuleAlertCount::alertCount).sum();
        recorder.succeed(batchRunId, scans.countTransactionsOn(date), alerts);
        return counts;
    }

    private int scanFanIn(long batchRunId, LocalDate date, ActiveRule rule) {
        FanInRule fanIn = FanInRule.from(rule.params());
        int created = 0;
        for (FanMinute m : scans.fanInMinutes(date, fanIn)) {
            if (!fanIn.fires(m)) {
                continue;
            }
            LocalDateTime start = fanIn.windowStart(m.minute());
            LocalDateTime end = fanIn.windowEnd(m.minute());
            FanEvidence evidence = scans.fanInEvidence(m.accountKey(), start, end);
            scans.insertAlert(new NewAlert(batchRunId, date, rule.refId(), m.accountKey(), evidence.entityId(),
                    start, end, end, evidence.toRuleFacts("distinct_senders", m.countNow()), evidence.transactionIds()));
            created++;
        }
        return created;
    }

    /** R01 — scanFanIn 과 같은 흐름, 방향만 송금(보낸 쪽 금액). */
    private int scanFanOut(long batchRunId, LocalDate date, ActiveRule rule) {
        FanOutRule fanOut = FanOutRule.from(rule.params());
        int created = 0;
        for (FanMinute m : scans.fanOutMinutes(date, fanOut)) {
            if (!fanOut.fires(m)) {
                continue;
            }
            LocalDateTime start = fanOut.windowStart(m.minute());
            LocalDateTime end = fanOut.windowEnd(m.minute());
            FanEvidence evidence = scans.fanOutEvidence(m.accountKey(), start, end);
            scans.insertAlert(new NewAlert(batchRunId, date, rule.refId(), m.accountKey(), evidence.entityId(),
                    start, end, end, evidence.toRuleFacts("distinct_receivers", m.countNow()), evidence.transactionIds()));
            created++;
        }
        return created;
    }
}
