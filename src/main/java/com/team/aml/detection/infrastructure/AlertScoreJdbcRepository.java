package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.AlertScoreRepository;
import com.team.aml.detection.domain.ScoreTarget;
import com.team.aml.shared.ai.AiDtos.ScoreResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Repository
public class AlertScoreJdbcRepository implements AlertScoreRepository {

    /** RuleScanJdbcRepository.SCAN_START_LOCK(5_005_001) 과 다른 값. */
    private static final long SCORE_START_LOCK = 5_005_002L;

    // 대상: PENDING + 다시 보낼 FAILED (10/29). alerts_batch_status_idx. 거래 ID 는 경보마다 배열로 한 번에.
    private static final String FIND_TARGETS = """
            select a.id, %s,
                   array(select t.transaction_id from ops.alert_transactions t
                         where t.alert_id = a.id order by t.transaction_id) as transaction_ids
            from ops.alerts a
            join ops.rules r on r.id = a.rule_ref_id
            where a.batch_date = ? and a.id > ?
              and (a.score_status = 'PENDING'
                   or (a.score_status = 'FAILED'
                       and (a.score_failure_code is null or a.score_failure_code = 'COMPUTE_ERROR')))
            order by a.id
            limit ?""".formatted(AlertPayloadRows.COLUMNS);
    // 조건부 — 이미 SCORED 면 0행이고 점수 행도 넣지 않는다 (alert_scores.alert_id UNIQUE 와 같이 중복을 막음).
    private static final String MARK_SCORED = """
            update ops.alerts set score_status = 'SCORED', score_failure_code = null
            where id = ? and score_status <> 'SCORED'""";
    // score·threshold 는 AI 값을 반올림 없이 (V005_1 numeric). payload = 항목 원문 + scoring_meta + produced_at.
    private static final String INSERT_SCORE = """
            insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version, prediction_id,
                                         produced_at, payload)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)""";
    private static final String MARK_FAILED = """
            update ops.alerts set score_status = 'FAILED', score_failure_code = ?
            where id = ? and score_status <> 'SCORED'""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public AlertScoreJdbcRepository(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public void lockScoreStart() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, SCORE_START_LOCK);
    }

    @Override
    public List<ScoreTarget> findScoreTargets(LocalDate date, long afterId, int limit) {
        return jdbc.query(FIND_TARGETS, (rs, rowNum) -> new ScoreTarget(rs.getLong("id"),
                AlertPayloadRows.payload(rs, ids(rs.getArray("transaction_ids")), json(rs.getString("rule_facts")))),
                date, afterId, limit);
    }

    @Override
    public boolean saveScore(long alertId, ScoreResult r, OffsetDateTime producedAt) {
        if (jdbc.update(MARK_SCORED, alertId) == 0) {
            return false;
        }
        jdbc.update(INSERT_SCORE, alertId, BigDecimal.valueOf(r.score()), BigDecimal.valueOf(r.threshold()),
                r.meta().modelVersion(), r.meta().featureVersion(), r.predictionId(), producedAt,
                text(r.payloadForStorage()));
        return true;
    }

    @Override
    public void markFailed(long alertId, String failureCode) {
        jdbc.update(MARK_FAILED, failureCode, alertId);
    }

    @Override
    public int failRunningScoreBatches(String message) {
        return jdbc.update("""
                update ops.batch_runs set status = 'FAILED', error_message = ?, finished_at = now()
                where batch_type = 'SCORE_REQUEST' and status = 'RUNNING'""", message);
    }

    private static List<Long> ids(Array array) throws SQLException {
        return Arrays.asList((Long[]) array.getArray());
    }

    private JsonNode json(String text) {
        try {
            return om.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 rule_facts 를 읽을 수 없습니다", e);
        }
    }

    private String text(JsonNode node) {
        try {
            return om.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("점수 payload 를 JSON 으로 쓸 수 없습니다", e);
        }
    }
}
