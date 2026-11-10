package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertScoreRepository;
import com.team.aml.detection.domain.ScoreTarget;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.ScoreResult;
import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 점수 배치 SCORE_REQUEST (SD 명세 API 4, flows/01 ③, SCRUM-100). 동기 — 응답에 건수 (안건 10).
 * start(RUNNING 커밋) → 대상을 max-batch 이하로 잘라 묶음마다 [AI 호출(트랜잭션 밖) → 저장(묶음 트랜잭션)] → finish.
 * <ul>
 *   <li>AiClient.scores() 는 넘긴 목록을 다 받은 뒤 돌려주므로 묶음 커밋을 위해 여기서 잘라 부른다(11/6 진희).</li>
 *   <li>묶음 호출이 실패하면(재시도 1회 뒤, AI_BAD_RESPONSE 포함) 그 묶음만 FAILED·코드 없음으로 커밋하고 다음 묶음으로 간다.
 *       앞 묶음의 SCORED 는 그대로(flows/04 4-1). 응답 alert_id 가 보낸 것과 다르면(빠짐·남음·중복) AiClient 가
 *       AI_BAD_RESPONSE 로 묶음째 거절한다 — 신뢰할 수 없는 응답은 저장하지 않는다(08_decisions 11/6, flows/01).</li>
 *   <li>다시 누르면 남은 대상만 보낸다. SCORED 는 다시 보내지 않아 alert_scores 가 두 번 생기지 않는다.</li>
 *   <li>대상 조회·저장처럼 묶음 호출이 아닌 곳에서 실패하면 FAILED 를 남기고 다시 던진다(500). 이미 커밋한 묶음은 그대로.</li>
 * </ul>
 */
@Service
public class RequestScores {

    private static final Logger log = LoggerFactory.getLogger(RequestScores.class);

    private final ScoreBatchRecorder recorder;
    private final ScoreChunkWriter writer;
    private final AlertScoreRepository scores;
    private final AiClient ai;
    private final BatchRunQuery batchRuns;
    private final CurrentUser currentUser;
    private final int chunkSize;

    public RequestScores(ScoreBatchRecorder recorder, ScoreChunkWriter writer, AlertScoreRepository scores,
                         AiClient ai, BatchRunQuery batchRuns, CurrentUser currentUser, AmlProperties props) {
        this.recorder = recorder;
        this.writer = writer;
        this.scores = scores;
        this.ai = ai;
        this.batchRuns = batchRuns;
        this.currentUser = currentUser;
        this.chunkSize = props.ai().maxBatch();
    }

    public ScoreBatchResult execute(LocalDate date) {
        long batchRunId = start(date, currentUser.require().userId());
        return run(batchRunId, date);
    }

    /** 409 RULE_SCAN_NOT_DONE·BATCH_RUNNING 이면 아무것도 남기지 않는다. */
    public long start(LocalDate date, long triggeredBy) {
        return recorder.start(date, triggeredBy);
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
        return new ScoreBatchResult(batchRuns.get(batchRunId), summary);
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
