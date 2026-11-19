package com.team.aml.detection.application;

import com.team.aml.detection.domain.ActiveRule;
import com.team.aml.detection.domain.CycleAlert;
import com.team.aml.detection.domain.CycleRule;
import com.team.aml.detection.domain.FanEvidence;
import com.team.aml.detection.domain.FanInRule;
import com.team.aml.detection.domain.FanMinute;
import com.team.aml.detection.domain.FanOutRule;
import com.team.aml.detection.domain.FxRelayEvidence;
import com.team.aml.detection.domain.FxRelayMinute;
import com.team.aml.detection.domain.FxRelayRule;
import com.team.aml.detection.domain.GatherScatterEvidence;
import com.team.aml.detection.domain.GatherScatterMinute;
import com.team.aml.detection.domain.GatherScatterRule;
import com.team.aml.detection.domain.NewAlert;
import com.team.aml.detection.domain.RuleScanRepository;
import com.team.aml.detection.domain.ScatterGatherAlert;
import com.team.aml.detection.domain.ScatterGatherLeg;
import com.team.aml.detection.domain.ScatterGatherRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 켜진 룰 전부를 한 트랜잭션으로 스캔한다 (SD 명세 §5). 하나라도 실패하면 그 실행의 경보가 전부 롤백 →
 * 다시 돌려도 두 번 생기지 않는다. alerts UNIQUE(rule_ref_id, account_key, window_start) 가 한 번 더 막는다.
 */
@Service
public class RuleScanner {

    private static final Logger log = LoggerFactory.getLogger(RuleScanner.class);

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
            int created = switch (rule.ruleId()) {
                case FanOutRule.RULE_ID -> scanFanOut(batchRunId, date, rule);
                case FanInRule.RULE_ID -> scanFanIn(batchRunId, date, rule);
                case FxRelayRule.RULE_ID -> scanFxRelay(batchRunId, date, rule);
                case CycleRule.RULE_ID -> scanCycle(batchRunId, date, rule);
                case ScatterGatherRule.RULE_ID -> scanScatterGather(batchRunId, date, rule);
                case GatherScatterRule.RULE_ID -> scanGatherScatter(batchRunId, date, rule);
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

    /** R06 — 입금·출금 상대 수가 둘 다 하한~상한 안이 되는 분에 경보. 근거는 창 안 입금(받은 쪽)·출금(보낸 쪽) 전부. */
    private int scanGatherScatter(long batchRunId, LocalDate date, ActiveRule rule) {
        GatherScatterRule gs = GatherScatterRule.from(rule.params());
        int created = 0;
        for (GatherScatterMinute m : scans.gatherScatterMinutes(date, gs)) {
            if (!gs.fires(m)) {
                continue;
            }
            LocalDateTime start = gs.windowStart(m.minute());
            LocalDateTime end = gs.windowEnd(m.minute());
            GatherScatterEvidence evidence = new GatherScatterEvidence(
                    scans.fanInEvidence(m.accountKey(), start, end), scans.fanOutEvidence(m.accountKey(), start, end));
            scans.insertAlert(new NewAlert(batchRunId, date, rule.refId(), m.accountKey(), evidence.entityId(),
                    start, end, end, evidence.toRuleFacts(m.sendersNow(), m.receiversNow()), evidence.transactionIds()));
            created++;
        }
        return created;
    }

    /** R03 — 중계 쌍을 완성하는 출금 분에, 1분 전 창에 쌍이 없으면 경보. 저장 전 쌍 검사(rules.md 2.1)가 어긋나면 만들지 않는다. */
    private int scanFxRelay(long batchRunId, LocalDate date, ActiveRule rule) {
        FxRelayRule fx = FxRelayRule.from(rule.params());
        int created = 0;
        for (FxRelayMinute m : scans.fxRelayMinutes(date, fx)) {
            if (!fx.fires(m)) {
                continue;
            }
            LocalDateTime start = fx.windowStart(m.minute());
            LocalDateTime end = fx.windowEnd(m.minute());
            FxRelayEvidence evidence = scans.fxRelayEvidence(m.accountKey(), start, end, fx);
            if (!evidence.valid(fx)) {
                log.warn("rule scan dropped alert failing path check rule_id={} reference_ts={} pairs={}",
                        FxRelayRule.RULE_ID, end, evidence.pairs().size());
                continue;
            }
            scans.insertAlert(new NewAlert(batchRunId, date, rule.refId(), m.accountKey(), evidence.entityId(),
                    start, end, end, evidence.toRuleFacts(fx), evidence.transactionIds()));
            created++;
        }
        return created;
    }

    /**
     * R05 — 그날 M→T 가 있는 분 E 마다 지금 창에 중간 계좌 N 이상인 출발 S 가 생기면 경보(1분 전 창엔 없을 때).
     * 저장 전 검사(rules.md 2.1)가 어긋나면 만들지 않는다.
     */
    private int scanScatterGather(long batchRunId, LocalDate date, ActiveRule rule) {
        ScatterGatherRule sg = ScatterGatherRule.from(rule.params());
        Map<String, List<ScatterGatherLeg>> byTarget = scans.scatterGatherLegs(date, sg).stream()
                .collect(Collectors.groupingBy(ScatterGatherLeg::target, TreeMap::new, Collectors.toList()));
        int created = 0;
        for (Map.Entry<String, List<ScatterGatherLeg>> entry : byTarget.entrySet()) {
            for (ScatterGatherAlert alert : sg.alerts(entry.getValue(), date)) {
                LocalDateTime end = sg.windowEnd(alert.minute());
                if (!alert.valid(sg)) {
                    log.warn("rule scan dropped alert failing path check rule_id={} reference_ts={} paths={}",
                            ScatterGatherRule.RULE_ID, end, alert.paths().size());
                    continue;
                }
                scans.insertAlert(new NewAlert(batchRunId, date, rule.refId(), entry.getKey(),
                        scans.entityIdOf(entry.getKey()), sg.windowStart(alert.minute()), end, end,
                        alert.toRuleFacts(), alert.transactionIds()));
                created++;
            }
        }
        return created;
    }

    /**
     * R04 — 그날 닫힌 고리를 (대상 계좌, 닫힌 분) 묶음으로 받아 묶음마다 경보 1건 (사건형, rules.md R04).
     * 묶음이 끝날 때마다 바로 저장해서 하루치 경로를 한꺼번에 들고 있지 않는다(scan 의 트랜잭션 안에서 커서로 받음).
     * 묶기는 CycleRule.alerts, 저장 전 경로 검사(rules.md 2.1)가 어긋나면 만들지 않는다.
     */
    private int scanCycle(long batchRunId, LocalDate date, ActiveRule rule) {
        CycleRule cr = CycleRule.from(rule.params());
        LocalDateTime dayStart = date.atStartOfDay();
        int[] created = {0};
        scans.forEachCycleGroup(dayStart, dayStart.plusDays(1), cr, group -> {
            for (CycleAlert alert : cr.alerts(group, date)) {
                LocalDateTime end = cr.windowEnd(alert.minute());
                if (!alert.valid(cr)) {
                    log.warn("rule scan dropped alert failing path check rule_id={} reference_ts={} paths={}",
                            CycleRule.RULE_ID, end, alert.paths().size());
                    continue;
                }
                scans.insertAlert(new NewAlert(batchRunId, date, rule.refId(), alert.accountKey(),
                        scans.entityIdOf(alert.accountKey()), cr.windowStart(alert.minute()), end, end,
                        alert.toRuleFacts(), alert.transactionIds()));
                created[0]++;
            }
        });
        return created[0];
    }
}
