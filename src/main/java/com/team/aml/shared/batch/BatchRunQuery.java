package com.team.aml.shared.batch;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 네 배치 공통 상태 조회 (SD 명세 §4). 실행자는 login_id 만 보여 준다. */
@Repository
public class BatchRunQuery {

    /** 허용 정렬 컬럼. 화면 이름 → SQL 컬럼. 동률은 id 로 순서를 고정한다. */
    private static final Map<String, String> SORT_COLUMNS = Map.of("started_at", "b.started_at", "batch_date", "b.batch_date");

    private static final String SELECT = """
            select b.id, b.batch_type, b.batch_date, b.status, b.started_at, b.finished_at,
                   b.rows_in, b.rows_out, b.error_message, u.login_id
            from ops.batch_runs b
            join ops.users u on u.id = b.triggered_by""";

    private static final RowMapper<BatchRunView> ROW = (rs, rowNum) -> new BatchRunView(
            rs.getLong("id"), BatchType.valueOf(rs.getString("batch_type")),
            rs.getObject("batch_date", LocalDate.class), BatchStatus.valueOf(rs.getString("status")),
            seconds(rs.getTimestamp("started_at")), seconds(rs.getTimestamp("finished_at")),
            (Integer) rs.getObject("rows_in"), (Integer) rs.getObject("rows_out"),
            rs.getString("error_message"), new BatchRunView.TriggeredBy(rs.getString("login_id")));

    private final JdbcTemplate jdbc;

    public BatchRunQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRunView> find(long batchRunId) {
        return jdbc.query(SELECT + " where b.id = ?", ROW, batchRunId).stream().findFirst();
    }

    public BatchRunView get(long batchRunId) {
        return find(batchRunId).orElseThrow(() -> new BatchRunNotFoundException(batchRunId));
    }

    /** sort 는 컨트롤러가 "started_at,desc" 형식을 검사한 값이다. */
    public Page<BatchRunView> search(BatchType type, LocalDate date, BatchStatus status, int page, int size, String sort) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (type != null) {
            conditions.add("b.batch_type = ?");
            args.add(type.name());
        }
        if (date != null) {
            conditions.add("b.batch_date = ?");
            args.add(date);
        }
        if (status != null) {
            conditions.add("b.status = ?");
            args.add(status.name());
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);

        String[] parts = sort.split(",");
        String column = SORT_COLUMNS.get(parts[0]);
        String direction = "asc".equals(parts[1]) ? "asc" : "desc";

        long total = jdbc.queryForObject("select count(*) from ops.batch_runs b" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<BatchRunView> items = jdbc.query(
                SELECT + where + " order by " + column + " " + direction + ", b.id " + direction + " limit ? offset ?",
                ROW, pageArgs.toArray());
        return new PageImpl<>(items, PageRequest.of(page, size), total);
    }

    private static Instant seconds(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
