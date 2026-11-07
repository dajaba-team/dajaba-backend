package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.AlertForCaseOpening;
import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.detection.domain.AlertRepository;
import com.team.aml.shared.ai.AiDtos.AlertPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertJdbcRepository implements AlertRepository {

    // SCORED 경보는 alert_scores 행이 반드시 있다 (V005_1). data_quality_status 는 payload 의 응답 항목에서 꺼낸다.
    private static final String FIND_FOR_CASE_OPENING = """
            select a.id, a.alert_no, a.account_key, a.entity_id, a.reference_ts,
                   s.id as score_id, s.score, s.threshold, s.model_version, s.feature_version,
                   s.payload ->> 'data_quality_status' as data_quality_status, s.prediction_id
            from ops.alerts a
            join ops.alert_scores s on s.alert_id = a.id
            where a.batch_date = ? and a.score_status = 'SCORED' and not a.case_opened
            order by a.id""";

    // 규약 §2 경보 JSON. alert_id 는 alert_no, rule_version 은 문자열. 점수가 아직 없으면 reasons 는 null.
    private static final String FIND_FOR_DRAFT = """
            select a.alert_no, a.account_key, a.entity_id, r.rule_id, r.version, r.typology_hint,
                   a.window_start, a.window_end, a.reference_ts, a.rule_facts::text as rule_facts, a.created_at,
                   (s.payload -> 'reasons')::text as reasons
            from ops.alerts a
            join ops.rules r on r.id = a.rule_ref_id
            left join ops.alert_scores s on s.alert_id = a.id
            where a.id = ?""";
    private static final String TRANSACTION_IDS =
            "select transaction_id from ops.alert_transactions where alert_id = ? order by transaction_id";
    // 같은 법인의 이 경보보다 앞선 경보. 같은 시각·자기 자신은 세지 않는다 (alerts_entity_reference_idx).
    private static final String PRIOR_ALERT_COUNT =
            "select count(*) from ops.alerts where entity_id = ? and reference_ts < ?";
    /** 데이터 시각은 규약 예시처럼 초까지. LocalDateTime.toString 은 초가 0이면 잘라서 쓰지 않는다. */
    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public AlertJdbcRepository(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public List<AlertForCaseOpening> findForCaseOpening(LocalDate batchDate) {
        return jdbc.query(FIND_FOR_CASE_OPENING, (rs, rowNum) -> new AlertForCaseOpening(
                rs.getLong("id"), rs.getString("alert_no"), rs.getString("account_key"), rs.getString("entity_id"),
                rs.getObject("reference_ts", LocalDateTime.class), rs.getLong("score_id"), rs.getBigDecimal("score"),
                rs.getBigDecimal("threshold"), rs.getString("model_version"), rs.getString("feature_version"),
                rs.getString("data_quality_status"), rs.getString("prediction_id")), batchDate);
    }

    @Override
    public boolean markCaseOpened(long alertId) {
        return jdbc.update("update ops.alerts set case_opened = true where id = ? and not case_opened", alertId) == 1;
    }

    @Override
    public boolean exists(long alertId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from ops.alerts where id = ?)", Boolean.class, alertId));
    }

    @Override
    public Optional<AlertForDraft> findForDraft(long alertId) {
        return jdbc.query(FIND_FOR_DRAFT, (rs, rowNum) -> {
            String entityId = rs.getString("entity_id");
            LocalDateTime referenceTs = rs.getObject("reference_ts", LocalDateTime.class);
            AlertPayload alert = new AlertPayload(rs.getString("alert_no"), rs.getString("account_key"), entityId,
                    rs.getString("rule_id"), String.valueOf(rs.getInt("version")), rs.getString("typology_hint"),
                    DATA_TIME.format(rs.getObject("window_start", LocalDateTime.class)),
                    DATA_TIME.format(rs.getObject("window_end", LocalDateTime.class)),
                    DATA_TIME.format(referenceTs),
                    jdbc.queryForList(TRANSACTION_IDS, Long.class, alertId),
                    json(rs.getString("rule_facts")),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()
                            .truncatedTo(ChronoUnit.SECONDS).toString());
            int prior = entityId == null ? 0
                    : jdbc.queryForObject(PRIOR_ALERT_COUNT, Integer.class, entityId, referenceTs);
            JsonNode reasons = json(rs.getString("reasons"));   // 점수가 없으면 [] — AI DraftRequest.reasons 는 목록(null 이면 422)
            return new AlertForDraft(alert, reasons == null ? om.createArrayNode() : reasons, prior);
        }, alertId).stream().findFirst();
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
