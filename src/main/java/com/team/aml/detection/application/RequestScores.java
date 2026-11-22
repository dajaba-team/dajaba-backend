package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertScoreRepository;
import com.team.aml.detection.domain.ScoreTarget;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.ScoreResult;
import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.security.CurrentUser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 점수 배치 SCORE_REQUEST (SD 명세 API 4, flows/01 ③, SCRUM-100). 화면 요청(execute)은 202 — 적재·룰 스캔과 같이 run 을
 * 앱 실행기에 넘기고, 건수는 batch_runs.summary 에 남아 상태 조회(B5)가 내려준다(SCRUM-349. 예전엔 동기 200 — 안건 10).
 * start(RUNNING 커밋) → 대상을 max-batch 이하로 잘라 묶음마다 [AI 호출(트랜잭션 밖) → 저장(묶음 트랜잭션)] → finish.
 * <ul>
 *   <li>AiClient.scores() 는 넘긴 목록을 다 받은 뒤 돌려주므로 묶음 커밋을 위해 여기서 잘라 부른다(11/6 진희).</li>
 *   <li>묶음 호출이 실패하면(재시도 1회 뒤, AI_BAD_RESPONSE 포함) 그 묶음만 FAILED·코드 없음으로 커밋하고 다음 묶음으로 간다.
 *       앞 묶음의 SCORED 는 그대로(flows/04 4-1). 응답 alert_id 가 보낸 것과 다르면(빠짐·남음·중복) AiClient 가
 *       AI_BAD_RESPONSE 로 묶음째 거절한다 — 신뢰할 수 없는 응답은 저장하지 않는다(08_decisions 11/6, flows/01).</li>
 *   <li>다시 누르면 남은 대상만 보낸다. SCORED 는 다시 보내지 않아 alert_scores 가 두 번 생기지 않는다.</li>
 *   <li>대상 조회·저장처럼 묶음 호출이 아닌 곳에서 실패하면 FAILED 를 남기고 다시 던진다(500). 이미 커밋한 묶음은 그대로.</li>
 *   <li>끝나면 지표 {@code aml.score.alerts{result}}·{@code aml.score.chunks{result}}를 세고, SUCCEEDED 면 배치 잇기(BatchChain)가
 *       켜져 있을 때 같은 날짜 케이스 생성으로 이어진다(SCRUM-349).</li>
 * </ul>
 */
@Service
public class RequestScores {

    private static final Logger log = LoggerFactory.getLogger(RequestScores.class);
    static final String NOT_STARTED_MESSAGE = "점수 작업을 시작하지 못함";

    private final ScoreBatchRecorder recorder;
    private final ScoreChunkWriter writer;
    private final AlertScoreRepository scores;
    private final AiClient ai;
    private final BatchRunQuery batchRuns;
    private final CurrentUser currentUser;
    private final int chunkSize;
    private final MeterRegistry meters;
    private final BatchChain chain;
    private final TaskExecutor executor;

    public RequestScores(ScoreBatchRecorder recorder, ScoreChunkWriter writer, AlertScoreRepository scores,
                         AiClient ai, BatchRunQuery batchRuns, CurrentUser currentUser, AmlProperties props,
                         MeterRegistry meters, BatchChain chain,
                         @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.recorder = recorder;
        this.writer = writer;
        this.scores = scores;
        this.ai = ai;
        this.batchRuns = batchRuns;
        this.currentUser = currentUser;
        this.chunkSize = props.ai().maxBatch();
        this.meters = meters;
        this.chain = chain;
        this.executor = executor;
        // 앱이 뜰 때 0으로 등록 — 첫 배치가 Prometheus increase() 에서 빠지지 않게
        countAlerts(0, 0, 0);
        countChunk(0, 0);
    }

    /**
     * 202 (SCRUM-349) — RunRuleScan.execute 와 같은 모양. 화면의 [점수 재처리]도 이 경로(다시 보낼 실패·남은 대상만).
     * 409 는 start 가 바로 던진다. 응답은 실행기에 넘기기 전에 읽고, 실행기가 거절하면 FAILED 로 기록하고 다시 던진다.
     */
    public BatchRunView execute(LocalDate date) {
        long batchRunId = start(date, currentUser.require().userId());
        BatchRunView accepted = batchRuns.get(batchRunId);
        try {
            executor.execute(() -> runInApp(batchRunId, date));
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, NOT_STARTED_MESSAGE + " (" + e.getClass().getSimpleName() + ")");
            log.error("점수 작업 제출 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            throw e;
        }
        return accepted;
    }

    /** 앱 실행기 경로 — run() 이 FAILED 를 남기고 다시 던진 예외는 run() 이 이미 로그를 남겼으니 삼킨다. */
    private void runInApp(long batchRunId, LocalDate date) {
        try {
            run(batchRunId, date);
        } catch (RuntimeException ignored) {
            // run() 의 catch 가 FAILED 기록·에러 로그를 남겼다
        }
    }

    /** 409 RULE_SCAN_NOT_DONE·BATCH_RUNNING 이면 아무것도 남기지 않는다. */
    public long start(LocalDate date, long triggeredBy) {
        return recorder.start(date, triggeredBy);
    }

    /** 배치 잇기(SCRUM-349)용 — since 뒤에 그 날짜 점수가 이미 시작됐으면 빈 값(시작 잠금 안에서 확인). 거절은 start 와 같다. */
    public OptionalLong startUnlessStartedSince(LocalDate date, long triggeredBy, Instant since) {
        return recorder.startUnlessStartedSince(date, triggeredBy, since);
    }

    public ScoreBatchResult run(long batchRunId, LocalDate date) {
        ScoreBatchSummary summary;
        try {
            summary = scoreAll(batchRunId, date);
            recorder.finish(batchRunId, summary);   // 종료 기록이 실패해도 RUNNING 으로 남아 그 날짜를 막지 않게 같이 잡는다
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, failureMessage(e));
            log.error("점수 배치 실패 batch_run_id={} date={} cause={}", batchRunId, date, e.getClass().getSimpleName());
            throw e;
        }
        log.info("점수 배치 batch_run_id={} date={} {}", batchRunId, date, summary.describe());
        ScoreBatchResult result = new ScoreBatchResult(batchRuns.get(batchRunId), summary);
        chain.afterScore(batchRunId, result.run().status());   // SUCCEEDED 면 같은 날짜 케이스 생성으로 이어짐
        return result;
    }

    /**
     * 경보 단위 결과 (SCRUM-349) — 묶음을 커밋한 직후마다 센다. 뒤 묶음에서 배치가 실패해도 이미 커밋된 결과는 빠지지 않는다
     * (다시 돌리면 SCORED 는 다시 보내지 않으니 끝에 한 번 세면 영영 빠짐). 실패율 = (failed_retryable + failed_final) / 전체.
     * failed_final 은 다시 보내도 같은 실패(RULE_NOT_SUPPORTED 등 코드 있는 실패). 세다 실패해도 점수 결과는 그대로(fail-open).
     */
    private void countAlerts(int scored, int failedRetryable, int failedFinal) {
        try {
            meters.counter("aml.score.alerts", "result", "scored").increment(scored);
            meters.counter("aml.score.alerts", "result", "failed_retryable").increment(failedRetryable);
            meters.counter("aml.score.alerts", "result", "failed_final").increment(failedFinal);
        } catch (RuntimeException e) {
            log.warn("점수 지표 기록 실패 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 묶음 단위 결과 — ok 는 저장까지 커밋한 묶음, ai_failed 는 AI 호출이 실패해 전부 FAILED 로 커밋한 묶음. */
    private void countChunk(int ok, int aiFailed) {
        try {
            meters.counter("aml.score.chunks", "result", "ok").increment(ok);
            meters.counter("aml.score.chunks", "result", "ai_failed").increment(aiFailed);
        } catch (RuntimeException e) {
            log.warn("점수 지표 기록 실패 cause={}", e.getClass().getSimpleName());
        }
    }

    private ScoreBatchSummary scoreAll(long batchRunId, LocalDate date) {
        int requested = 0, scored = 0, failed = 0, retryable = 0, chunks = 0, chunksFailed = 0;
        SortedSet<String> models = new TreeSet<>();
        long afterId = 0;
        while (true) {
            List<ScoreTarget> chunk = scores.findScoreTargets(date, afterId, chunkSize);
            if (chunk.isEmpty()) {
                break;
            }
            afterId = chunk.get(chunk.size() - 1).alertId();
            chunks++;
            requested += chunk.size();
            List<ScoreResult> results;
            try {
                results = ai.scores(batchRunId, chunk.stream().map(ScoreTarget::payload).toList()).scores();
            } catch (AiClientException e) {
                writer.markAllFailed(chunk);
                countAlerts(0, chunk.size(), 0);
                countChunk(0, 1);
                chunksFailed++;
                failed += chunk.size();
                retryable += chunk.size();
                log.warn("점수 묶음 실패 batch_run_id={} chunk={} size={} code={}",
                        batchRunId, chunks, chunk.size(), e.code());
                continue;
            }
            results.forEach(r -> {
                if (r.meta() != null && r.meta().modelVersion() != null) {
                    models.add(r.meta().modelVersion());
                }
            });
            ScoreChunkWriter.Counts c = writer.save(chunk, results);
            countAlerts(c.scored(), c.retryable(), c.failed() - c.retryable());
            countChunk(1, 0);
            scored += c.scored();
            failed += c.failed();
            retryable += c.retryable();
        }
        return new ScoreBatchSummary(requested, scored, failed, retryable, chunks, chunksFailed, List.copyOf(models));
    }

    /** 업무 오류는 코드·메시지, 그 밖은 예외 이름만 — SQL·스택이 batch_runs 에 들어가지 않게. */
    static String failureMessage(RuntimeException e) {
        return e instanceof DomainException d ? d.code() + ": " + d.getMessage()
                : "점수 배치 실패 (" + e.getClass().getSimpleName() + ")";
    }
}
