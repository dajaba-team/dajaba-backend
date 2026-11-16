package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.AlertReviewDetail;
import com.team.aml.detection.domain.AlertReviewFilter;
import com.team.aml.detection.domain.AlertReviewItem;
import com.team.aml.detection.domain.AlertReviewOrder;
import com.team.aml.detection.domain.AlertReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 관리자 경보 점검 (SCRUM-198). 정답 칸(is_laundering·pattern_*)은 고르지 않는다. 점수가 없으면 s.* 는 null.
 * 날짜·점수 상태 필터는 alerts_batch_status_idx(batch_date, score_status)를 탄다.
 */
@Repository
public class AlertReviewJdbcRepository implements AlertReviewRepository {

    private static final String FROM = """
            from ops.alerts a
            join ops.rules r on r.id = a.rule_ref_id
            left join ops.alert_scores s on s.alert_id = a.id""";
    private static final String ITEM_COLUMNS = """
            select a.id, a.alert_no, a.batch_date, r.rule_id, r.version as rule_version, r.typology_hint,
                   a.account_key, a.window_start, a.window_end, a.reference_ts,
                   (select count(*) from ops.alert_transactions t where t.alert_id = a.id) as transaction_count,
                   a.score_status, a.score_failure_code, s.score, s.threshold, s.model_version,
                   s.payload ->> 'data_quality_status' as data_quality_status, a.case_opened, a.created_at""";
    private static final String DETAIL = ITEM_COLUMNS + """
            ,
                   a.rule_facts::text as rule_facts, s.feature_version, s.prediction_id, s.produced_at,
                   (s.payload -> 'reasons')::text as reasons,
                   (s.payload -> 'evidence_transaction_ids')::text as evidence_transaction_ids,
                   (s.payload -> 'context_transaction_ids')::text as context_transaction_ids
            """ + FROM + " where a.alert_no = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public AlertReviewJdbcRepository(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public List<AlertReviewItem> search(AlertReviewFilter filter, AlertReviewOrder order, long offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(ITEM_COLUMNS + "\n" + FROM + where + " order by " + orderBy(order) + " limit ? offset ?",
                (rs, rowNum) -> item(rs), args.toArray());
    }

    @Override
    public long count(AlertReviewFilter filter) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("select count(*) " + FROM + where(filter, args), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    @Override
    public Optional<AlertReviewDetail> findDetail(String alertNo) {
        return jdbc.query(DETAIL, (rs, rowNum) -> new AlertReviewDetail(item(rs), json(rs.getString("rule_facts")),
                rs.getString("feature_version"), rs.getString("prediction_id"), seconds(rs.getTimestamp("produced_at")),
                arrayOrEmpty(rs.getString("reasons")), arrayOrEmpty(rs.getString("evidence_transaction_ids")),
                arrayOrEmpty(rs.getString("context_transaction_ids"))), alertNo).stream().findFirst();
    }

    @Override
    public Optional<Long> findId(String alertNo) {
        return jdbc.queryForList("select id from ops.alerts where alert_no = ?", Long.class, alertNo)
                .stream().findFirst();
    }

    /** 값은 전부 ? 로 — 사용자 입력을 SQL 에 이어 붙이지 않는다. */
    private static String where(AlertReviewFilter f, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (f.date() != null) {
            conditions.add("a.batch_date = ?");
            args.add(f.date());
        }
        if (f.ruleId() != null) {
            conditions.add("r.rule_id = ?");
            args.add(f.ruleId());
        }
        if (f.scoreStatus() != null) {
            conditions.add("a.score_status = ?");
            args.add(f.scoreStatus());
        }
        if (f.scoreFailureCode() != null) {
            conditions.add("a.score_failure_code = ?");
            args.add(f.scoreFailureCode());
        }
        if (f.caseOpened() != null) {
            conditions.add("a.case_opened = ?");
            args.add(f.caseOpened());
        }
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    /** enum 에서만 고른다. 점수 없는 경보는 오름·내림 모두 맨 뒤, 같은 값은 id 순. */
    private static String orderBy(AlertReviewOrder order) {
        return switch (order) {
            case SCORE_DESC -> "s.score desc nulls last, a.id";
            case SCORE_ASC -> "s.score asc nulls last, a.id";
            case ALERT_NO_ASC -> "a.alert_no";
            case ALERT_NO_DESC -> "a.alert_no desc";
            case CREATED_AT_ASC -> "a.created_at, a.id";
            case CREATED_AT_DESC -> "a.created_at desc, a.id desc";
        };
    }

    private static AlertReviewItem item(ResultSet rs) throws SQLException {
        return new AlertReviewItem(rs.getLong("id"), rs.getString("alert_no"),
                rs.getObject("batch_date", LocalDate.class), rs.getString("rule_id"), rs.getInt("rule_version"),
                rs.getString("typology_hint"), rs.getString("account_key"),
                rs.getObject("window_start", LocalDateTime.class), rs.getObject("window_end", LocalDateTime.class),
                rs.getObject("reference_ts", LocalDateTime.class), rs.getInt("transaction_count"),
                rs.getString("score_status"), rs.getString("score_failure_code"), rs.getBigDecimal("score"),
                rs.getBigDecimal("threshold"), rs.getString("model_version"), rs.getString("data_quality_status"),
                rs.getBoolean("case_opened"), seconds(rs.getTimestamp("created_at")));
    }

    private static Instant seconds(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 점수가 없거나 칸이 없으면 [] (계약서 2.3). */
    private JsonNode arrayOrEmpty(String text) {
        JsonNode node = json(text);
        return node == null ? om.createArrayNode() : node;
    }

    /** null·JSON null 은 null 로. */
    private JsonNode json(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = om.readTree(text);
            return node.isNull() ? null : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 JSON 을 읽을 수 없습니다", e);
        }
    }
}
