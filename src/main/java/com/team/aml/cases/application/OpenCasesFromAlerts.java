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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

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

    @Value("${AML_DEMO_CASE_SELECTION_FILE:}")
    private String demoCaseSelectionFile = "";

    @Value("${AML_DEMO_CASE_SELECTION_SHA256:}")
    private String demoCaseSelectionSha256 = "";

    public OpenCasesFromAlerts(FindAlertsForCaseOpening findAlerts, OpenCaseForAlert openOne,
                               OpenCasesRecorder recorder, CasePolicySource policySource, CaseRepository cases) {
        this.findAlerts = findAlerts;
        this.openOne = openOne;
        this.recorder = recorder;
        this.policySource = policySource;
        this.cases = cases;
    }

    /**
     * 정책이 비어 있거나(POLICY_NOT_CONFIGURED), 같은 날짜가 실행 중(409 BATCH_RUNNING)이면
     * batch_runs 행을 만들기 전에 거절한다.
     *
     * @return batch_run_id
     */
    public long start(LocalDate batchDate, long triggeredBy) {
        policySource.current();
        return recorder.start(batchDate, triggeredBy);
    }

    /** 배치 잇기(SCRUM-349)용 — since 뒤에 그 날짜 케이스 생성이 이미 시작됐으면 빈 값(시작 잠금 안에서 확인). 거절은 start 와 같다. */
    public OptionalLong startUnlessStartedSince(LocalDate batchDate, long triggeredBy, Instant since) {
        policySource.current();
        return recorder.startUnlessStartedSince(batchDate, triggeredBy, since);
    }

    /**
     * 예외를 던지지 않는다. 결과는 batch_runs 에 남는다.
     * NOT_SUPPORTED — 배치 전체에 트랜잭션을 걸지 않는다는 위 원칙을, 부르는 쪽이 트랜잭션 안이어도 지킨다.
     * RunOpenCasesOnce 가 실행 잠금용 트랜잭션 안에서 부르는데, 그 트랜잭션에 경보 조회 등이 끼면 한 군데 예외로
     * 바깥 트랜잭션이 rollback-only 가 되어 버린다. 부르는 쪽 트랜잭션은 잠시 멈췄다가(잠금은 그대로) 끝나면 이어진다.
     *
     * @return 경보 단위 집계. 대상 조회처럼 경보 단위가 아닌 곳에서 실패하면 빈 값(batch_runs 는 FAILED)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        boolean demoSelection = !demoCaseSelectionFile.isBlank();
        if (demoSelection) {
            alerts = DemoCaseSelection.select(demoCaseSelectionFile, demoCaseSelectionSha256, batchDate, alerts);
        }
        int opened = 0, notHigh = 0, alreadyOpened = 0, failed = 0;
        for (AlertForCaseOpening alert : alerts) {
            try {
                ScoreClassification grade = policy.classify(alert.score(), alert.recommendedThreshold());
                if (!demoSelection && grade.band() != ScoreBand.HIGH) {
                    notHigh++;
                    continue;
                }
                openOne.execute(alert, grade);
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
