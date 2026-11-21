package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import com.team.aml.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 배치 잇기 3단계 (SCRUM-349, 운영 도구 2절 — cases 쪽) — 성공한 점수(batch_run_id)의 날짜로 케이스 생성을 요청한다.
 * 요청자는 점수를 요청한 사람(triggered_by). 그 뒤는 화면 요청과 같은 {@link RequestOpenCases} 경로
 * (RUNNING 커밋 → case.open-cases 큐 또는 앱 안 실행기 → RunOpenCasesOnce)라 경보 처리 규칙은 그대로다.
 * <ul>
 *   <li><b>같은 메시지가 다시 와도 한 번만</b>: 점수가 끝난 뒤 그 날짜 케이스 생성이 이미 시작됐으면(연쇄든 직접이든) 건너뛴다.
 *       이 확인은 케이스 생성 시작 잠금 안에서 한다 — 밖에서 보면 확인과 시작 사이에 직접 실행이 끝나 한 번 더 돌 수 있다.</li>
 *   <li>시작 거절(409 BATCH_RUNNING·POLICY_NOT_CONFIGURED)은 건너뛴다(ACK).</li>
 *   <li>그 밖의 예외(DB 오류·실행기 거절)는 내보내 큐가 재시도한다. 실행기 거절은 RequestOpenCases 가 FAILED 를 남기므로
 *       재전달 때 위 규칙으로 건너뛴다.</li>
 * </ul>
 */
@Service
public class OpenCasesAfterScore {

    private static final Logger log = LoggerFactory.getLogger(OpenCasesAfterScore.class);

    /** 처리 결과 — 로그·테스트용. */
    public enum Outcome { REQUESTED, SKIPPED_NOT_FOUND, SKIPPED_NOT_SUCCEEDED, SKIPPED_ALREADY_STARTED, SKIPPED_REJECTED }

    private final BatchRunRepository batchRuns;
    private final RequestOpenCases requestOpenCases;

    public OpenCasesAfterScore(BatchRunRepository batchRuns, RequestOpenCases requestOpenCases) {
        this.batchRuns = batchRuns;
        this.requestOpenCases = requestOpenCases;
    }

    public Outcome execute(long scoreRunId) {
        Optional<BatchRun> found = batchRuns.findById(scoreRunId)
                .filter(r -> r.getBatchType() == BatchType.SCORE_REQUEST);
        if (found.isEmpty()) {
            log.warn("배치 잇기 메시지 버림 batch_run_id={} 점수 행 없음", scoreRunId);
            return Outcome.SKIPPED_NOT_FOUND;
        }
        BatchRun score = found.get();
        if (score.getStatus() != BatchStatus.SUCCEEDED) {
            log.info("배치 잇기 건너뜀 batch_run_id={} 점수 {}", scoreRunId, score.getStatus());
            return Outcome.SKIPPED_NOT_SUCCEEDED;
        }
        LocalDate date = score.getBatchDate();
        Instant scoreEnd = score.getFinishedAt() != null ? score.getFinishedAt() : score.getStartedAt();
        try {
            Optional<RequestOpenCases.Accepted> accepted =
                    requestOpenCases.executeUnlessStartedSince(date, score.getTriggeredBy(), scoreEnd);
            if (accepted.isEmpty()) {
                log.info("배치 잇기 건너뜀 batch_run_id={} 그 뒤 케이스 생성이 이미 시작됨", scoreRunId);
                return Outcome.SKIPPED_ALREADY_STARTED;
            }
            log.info("배치 잇기 케이스 생성 요청 batch_run_id={} → {}", scoreRunId, accepted.get().batchRunId());
            return Outcome.REQUESTED;
        } catch (DomainException e) {
            log.info("배치 잇기 건너뜀 batch_run_id={} 케이스 생성 시작 거절 code={}", scoreRunId, e.code());
            return Outcome.SKIPPED_REJECTED;
        }
    }
}
