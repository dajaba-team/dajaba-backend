package com.team.aml.cases.application;

import com.team.aml.cases.domain.CasePolicy;
import com.team.aml.cases.domain.CasePolicySource;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.ScoreBand;
import com.team.aml.cases.domain.ScoreClassification;
import com.team.aml.detection.application.FindAlertsForCaseOpening;
import com.team.aml.detection.domain.AlertForCaseOpening;
import com.team.aml.detection.domain.CaseAlreadyOpenedException;
import com.team.aml.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 케이스 생성 배치 OPEN_CASES (CASES 명세 API-14, flows/01 ④). cases 가 detection 을 부른다(반대 아님).
 * start() 가 RUNNING 을 커밋하고, run() 이 경보를 하나씩 처리한 뒤 결과를 기록한다.
 * 컨트롤러(POST /admin/batches/open-cases, 202)는 다음 PR — 적재와 같이 start() 뒤 run() 을 비동기로 부른다.
 *
 * <p><b>배치 전체에는 트랜잭션을 걸지 않는다.</b> 경보마다 OpenCaseForAlert(REQUIRES_NEW) 가 케이스 + OPEN 이력 + MarkCaseOpened
 * 를 한 트랜잭션으로 묶고, 한 건이 실패하면 그 건만 롤백하고 다음 경보로 간다.
 * <ul>
 *   <li>CasePolicy 가 HIGH 가 아니면 만들지 않는다(안건 2 — detection 은 미리 거르지 않음).</li>
 *   <li>409 CASE_ALREADY_OPENED, 또는 제약 위반인데 그 경보의 케이스가 실제로 있음(cases.alert_id 중복) — 다른 실행이
 *       먼저 만든 경보, 건너뜀. 케이스가 없는 제약 위반은 진짜 오류라 실패로 센다.</li>
 *   <li>그 밖의 오류 — 실패로 세고 계속. 하나라도 있으면 batch_runs 는 FAILED(건수 메시지).</li>
 * </ul>
 */
@Service
public class OpenCasesFromAlerts {

    private static final Logger log = LoggerFactory.getLogger(OpenCasesFromAlerts.class);

    private final FindAlertsForCaseOpening findAlerts;
    private final OpenCaseForAlert openOne;
    private final OpenCasesRecorder recorder;
    private final CasePolicySource policySource;
    private final CaseRepository cases;

    public OpenCasesFromAlerts(FindAlertsForCaseOpening findAlerts, OpenCaseForAlert openOne,
                               OpenCasesRecorder recorder, CasePolicySource policySource, CaseRepository cases) {
        this.findAlerts = findAlerts;
        this.openOne = openOne;
        this.recorder = recorder;
        this.policySource = policySource;
        this.cases = cases;
    }

    /**
     * 정책이 비어 있거나(POLICY_NOT_CONFIGURED), 같은 날짜가 실행 중(409 BATCH_RUNNING)·이미 성공(409 BATCH_ALREADY_DONE)이면
     * batch_runs 행을 만들기 전에 거절한다.
     *
     * @return batch_run_id
     */
    public long start(LocalDate batchDate, long triggeredBy) {
        policySource.current();
        return recorder.start(batchDate, triggeredBy);
    }

    /**
     * 예외를 던지지 않는다. 결과는 batch_runs 에 남는다.
     *
     * @return 경보 단위 집계. 대상 조회처럼 경보 단위가 아닌 곳에서 실패하면 빈 값(batch_runs 는 FAILED)
     */
    public Optional<OpenCasesSummary> run(long batchRunId, LocalDate batchDate) {
        try {
            OpenCasesSummary summary = openAll(batchDate);
            recorder.finish(batchRunId, summary);
            log.info("케이스 생성 batch_run_id={} date={} {}", batchRunId, batchDate, summary.describe());
            return Optional.of(summary);
        } catch (RuntimeException e) {
            // 대상 조회·정책 읽기처럼 경보 단위가 아닌 실패. 이미 만든 케이스는 각자 커밋돼 있다.
            recorder.fail(batchRunId, failureMessage(e));
            log.error("케이스 생성 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private OpenCasesSummary openAll(LocalDate batchDate) {
        CasePolicy policy = policySource.current();
        List<AlertForCaseOpening> alerts = findAlerts.execute(batchDate);
        int opened = 0, notHigh = 0, alreadyOpened = 0, failed = 0;
        for (AlertForCaseOpening alert : alerts) {
            try {
                ScoreClassification grade = policy.classify(alert.score(), alert.recommendedThreshold());
                if (grade.band() != ScoreBand.HIGH) {
                    notHigh++;
                    continue;
                }
                openOne.execute(alert);
                opened++;
            } catch (CaseAlreadyOpenedException e) {
                alreadyOpened++;
                log.info("케이스 생성 건너뜀 alert_id={} 이미 케이스 있음", alert.alertId());
            } catch (DataIntegrityViolationException e) {
                // 그 경보 트랜잭션은 이미 롤백됐다. 케이스가 실제로 있을 때만 "먼저 만들어짐", 아니면 진짜 제약 위반
                if (cases.existsByAlertId(alert.alertId())) {
                    alreadyOpened++;
                    log.info("케이스 생성 건너뜀 alert_id={} 이미 케이스 있음", alert.alertId());
                } else {
                    failed++;
                    log.warn("케이스 생성 실패 alert_id={} cause={}", alert.alertId(), e.getClass().getSimpleName());
                }
            } catch (RuntimeException e) {
                failed++;
                // 경보 내용·DB 메시지(행 값이 들어갈 수 있음)는 남기지 않고 id·종류만
                log.warn("케이스 생성 실패 alert_id={} cause={}", alert.alertId(), e.getClass().getSimpleName());
            }
        }
        return new OpenCasesSummary(alerts.size(), opened, notHigh, alreadyOpened, failed);
    }

    private static String failureMessage(RuntimeException e) {
        return e instanceof DomainException ? e.getMessage() : "케이스 생성 실패 (" + e.getClass().getSimpleName() + ")";
    }
}
