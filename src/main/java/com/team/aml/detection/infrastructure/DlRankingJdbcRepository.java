package com.team.aml.detection.infrastructure;

import com.team.aml.detection.domain.DlRankingAlertRow;
import com.team.aml.detection.domain.DlRankingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class DlRankingJdbcRepository implements DlRankingRepository {

    // 한 문장 — 경보·룰·점수를 같은 스냅샷에서. 점수 행은 경보당 하나(alert_scores.alert_id UNIQUE)라 줄이 늘지 않는다.
    private static final String FIND_BY_DATE = """
            select a.alert_no, r.rule_id, s.score, s.model_version
            from ops.alerts a
            join ops.rules r on r.id = a.rule_ref_id
            left join ops.alert_scores s on s.alert_id = a.id
            where a.batch_date = ?
            order by a.alert_no""";

    private final JdbcTemplate jdbc;

    public DlRankingJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DlRankingAlertRow> findByBatchDate(LocalDate batchDate) {
        return jdbc.query(FIND_BY_DATE, (rs, i) -> new DlRankingAlertRow(rs.getString("alert_no"),
                rs.getString("rule_id"), rs.getBigDecimal("score"), rs.getString("model_version")), batchDate);
    }
}
