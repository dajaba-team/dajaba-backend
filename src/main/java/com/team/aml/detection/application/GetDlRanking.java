package com.team.aml.detection.application;

import com.team.aml.detection.application.DlRankingResult.Available;
import com.team.aml.detection.application.DlRankingResult.DlRank;
import com.team.aml.detection.application.DlRankingResult.Reason;
import com.team.aml.detection.application.DlRankingResult.Unavailable;
import com.team.aml.detection.domain.DlRankingAlertRow;
import com.team.aml.detection.domain.DlRankingRepository;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.DlRankedAlert;
import com.team.aml.shared.ai.AiDtos.DlRankingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ML+DL 조사 순위 대조 조회 (규약 6절 v0.4, SCRUM-376). AI `getDlRankings` 를 부르고 그 날짜 경보·점수와 맞춰 본 뒤
 * {@link DlRankingResult} 를 돌려준다. 오래된 점수와 새 순위가 섞이지 않게 하나라도 어긋나면 순위 전체를 버린다.
 * <ul>
 *   <li>AI 호출은 트랜잭션 밖 — `NOT_SUPPORTED` 로 부르는 쪽 트랜잭션도 멈춘다. 다만 멈춘 바깥 트랜잭션의 커넥션은
 *       그대로 잡혀 있으므로 부르는 쪽(케이스 조회)은 조회 트랜잭션을 끝낸 뒤 밖에서 부른다.
 *       DB 읽기는 쿼리 하나(한 문장 = 한 스냅샷)</li>
 *   <li>예외 안 나감은 AI 호출·대조 쪽 범위. AI 가 던진 예외는 전부 {@link Reason#AI_UNAVAILABLE}.
 *       <b>DB 예외는 그대로 던진다</b> — 순위가 없는 것과 DB 가 죽은 것을 같은 "조회 불가"로 숨기지 않게</li>
 *   <li>이유는 집합 → 룰 → 모델 → 점수 순으로 보고 처음 하나. `alert_no` 는 전역 시퀀스라 처음 스캔이 아니면
 *       같은 건수여도 번호가 밀린다 — 그래서 집합을 먼저 본다</li>
 *   <li>저장·캐시 없음. 채점·CasePolicy·케이스 생성에서 부르지 않는다. 로그는 날짜·건수·이유·시간만</li>
 * </ul>
 */
@Service
public class GetDlRanking {

    private static final Logger log = LoggerFactory.getLogger(GetDlRanking.class);

    /** DB `score`(numeric) 와 응답 `ml_score` 허용 차 — 십진수로 비교(정확히 1e-6 은 통과). */
    static final BigDecimal SCORE_TOLERANCE = new BigDecimal("0.000001");

    private final AiClient aiClient;
    private final DlRankingRepository repository;

    public GetDlRanking(AiClient aiClient, DlRankingRepository repository) {
        this.aiClient = aiClient;
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DlRankingResult execute(LocalDate scanDate) {
        Objects.requireNonNull(scanDate, "scanDate");
        long started = System.nanoTime();
        DlRankingResponse response;
        try {
            response = aiClient.getDlRankings(scanDate);
        } catch (RuntimeException e) {
            String code = e instanceof AiClientException ace ? ace.code() : e.getClass().getSimpleName();
            log.info("dl_ranking scan_date={} result=UNAVAILABLE reason={} ai_code={} ms={}",
                    scanDate, Reason.AI_UNAVAILABLE, code, elapsedMs(started));
            return new Unavailable(Reason.AI_UNAVAILABLE);
        }
        List<DlRankingAlertRow> rows = repository.findByBatchDate(scanDate);   // DB 예외는 그대로
        Reason mismatch = firstMismatch(response, rows);
        if (mismatch != null) {
            log.info("dl_ranking scan_date={} result=UNAVAILABLE reason={} alerts={} db_alerts={} ms={}",
                    scanDate, mismatch, response.alerts().size(), rows.size(), elapsedMs(started));
            return new Unavailable(mismatch);
        }
        Map<String, DlRank> ranks = new LinkedHashMap<>();
        for (DlRankedAlert a : response.alerts()) {
            ranks.put(a.alertId(), new DlRank(a.mlRank(), a.fusedRank(), a.fallbackReason(), a.dlScore()));
        }
        log.info("dl_ranking scan_date={} result=AVAILABLE alerts={} ms={}",
                scanDate, ranks.size(), elapsedMs(started));
        return new Available(scanDate, response.snapshotSha256(), response.mlModel(), response.dlModel(), ranks);
    }

    /** 집합 → 룰 → 모델 → 점수. 단계마다 경보 전부를 본 뒤 다음 단계로. 다 맞으면 null. */
    private static Reason firstMismatch(DlRankingResponse response, List<DlRankingAlertRow> rows) {
        Map<String, DlRankingAlertRow> byNo = new HashMap<>();
        for (DlRankingAlertRow row : rows) {
            byNo.put(row.alertNo(), row);
        }
        List<DlRankedAlert> alerts = response.alerts();
        if (alerts.size() != rows.size() || byNo.size() != rows.size()) {
            return Reason.ALERT_SET_MISMATCH;
        }
        for (DlRankedAlert a : alerts) {
            if (!byNo.containsKey(a.alertId())) return Reason.ALERT_SET_MISMATCH;
        }
        for (DlRankedAlert a : alerts) {
            if (!Objects.equals(byNo.get(a.alertId()).ruleId(), a.ruleId())) return Reason.RULE_MISMATCH;
        }
        for (DlRankingAlertRow row : rows) {
            if (row.score() != null && !Objects.equals(row.modelVersion(), response.mlModel())) {
                return Reason.MODEL_MISMATCH;
            }
        }
        for (DlRankedAlert a : alerts) {
            DlRankingAlertRow row = byNo.get(a.alertId());
            if ((row.score() == null) != (a.mlScore() == null)) return Reason.SCORE_MISMATCH;
            if (row.score() != null
                    && row.score().subtract(BigDecimal.valueOf(a.mlScore())).abs().compareTo(SCORE_TOLERANCE) > 0) {
                return Reason.SCORE_MISMATCH;
            }
        }
        return null;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
