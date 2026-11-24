package com.team.aml.detection.infrastructure;

import com.team.aml.detection.domain.DlRankingAlertRow;
import com.team.aml.detection.domain.DlRankingRepository;
import com.team.aml.detection.domain.EvidenceSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class DlRankingJdbcRepository implements DlRankingRepository {

    // 경보당 한 줄. 두 근거 배열은 같은 순서로 묶어 파일명과 행번호의 짝을 맞춘다.
    private static final String FIND_BY_DATE = """
            select a.alert_no, r.rule_id, s.score, s.model_version, a.reference_ts,
                   array_agg(f.file_name order by f.file_name, t.source_row_number)
                       filter (where t.transaction_id is not null) as ev_files,
                   array_agg(t.source_row_number order by f.file_name, t.source_row_number)
                       filter (where t.transaction_id is not null) as ev_rows
            from ops.alerts a
            join ops.rules r on r.id = a.rule_ref_id
            left join ops.alert_scores s on s.alert_id = a.id
            left join ops.alert_transactions x on x.alert_id = a.id
            left join ops.transactions t on t.transaction_id = x.transaction_id
            left join ops.source_files f on f.id = t.source_file_id
            where a.batch_date = ?
            group by a.id, a.alert_no, r.rule_id, s.score, s.model_version, a.reference_ts
            order by a.alert_no""";

    private final JdbcTemplate jdbc;

    public DlRankingJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DlRankingAlertRow> findByBatchDate(LocalDate batchDate) {
        return jdbc.query(FIND_BY_DATE, (rs, i) -> new DlRankingAlertRow(rs.getString("alert_no"),
                rs.getString("rule_id"), rs.getBigDecimal("score"), rs.getString("model_version"),
                rs.getObject("reference_ts", LocalDateTime.class),
                evidence(rs.getArray("ev_files"), rs.getArray("ev_rows"))), batchDate);
    }

    private static List<EvidenceSource> evidence(Array files, Array rows) throws SQLException {
        if (files == null || rows == null) return List.of();
        String[] fileNames = (String[]) files.getArray();
        Long[] rowNumbers = (Long[]) rows.getArray();
        List<EvidenceSource> evidence = new ArrayList<>(fileNames.length);
        for (int i = 0; i < fileNames.length; i++) {
            evidence.add(new EvidenceSource(fileNames[i], rowNumbers[i]));
        }
        return evidence;
    }
}
