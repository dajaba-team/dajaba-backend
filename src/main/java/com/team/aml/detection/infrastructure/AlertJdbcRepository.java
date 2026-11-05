package com.team.aml.detection.infrastructure;

import com.team.aml.detection.domain.AlertForCaseOpening;
import com.team.aml.detection.domain.AlertRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    private final JdbcTemplate jdbc;

    public AlertJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
}
