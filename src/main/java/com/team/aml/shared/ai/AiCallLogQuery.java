package com.team.aml.shared.ai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 호출 기록 조회 (SCRUM-134). 읽기 전용 — AiCallLogRepository 는 추가 전용(save)만 그대로 둔다 (원칙 3).
 * 필터는 전부 선택이고 같이 쓰면 AND. 정렬은 created_at 만, 동률은 id 로 순서를 고정한다.
 */
@Repository
public class AiCallLogQuery {

    /** SUCCESS = error_code 없음, FAILURE = error_code 있음 (2xx 빈 본문의 AI_BAD_RESPONSE·타임아웃 null 포함). */
    public enum Outcome { SUCCESS, FAILURE }

    /** from 포함·to 제외. null 칸은 조건에 넣지 않는다. */
    public record Filter(String endpoint, String requestId, String targetType, String targetId,
                         String errorCode, Outcome outcome, Instant from, Instant to) {}

    private static final String SELECT = """
            select id, endpoint, request_id, attempt, status_code, error_code, duration_ms,
                   ai_version, generation_id, target_type, target_id, created_at
            from ops.ai_call_logs""";

    private static final RowMapper<AiCallLogView> ROW = (rs, rowNum) -> new AiCallLogView(
            rs.getLong("id"), rs.getString("endpoint"), rs.getString("request_id"), rs.getInt("attempt"),
            (Integer) rs.getObject("status_code"), rs.getString("error_code"), rs.getInt("duration_ms"),
            rs.getString("ai_version"), rs.getString("generation_id"),
            rs.getString("target_type"), rs.getString("target_id"),
            seconds(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public AiCallLogQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** sort 는 컨트롤러가 "created_at,desc" 형식을 검사한 값이다. */
    public Page<AiCallLogView> search(Filter f, int page, int size, String sort) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        equal(conditions, args, "endpoint", f.endpoint());
        equal(conditions, args, "request_id", f.requestId());
        equal(conditions, args, "target_type", f.targetType());
        equal(conditions, args, "target_id", f.targetId());
        equal(conditions, args, "error_code", f.errorCode());
        if (f.outcome() == Outcome.SUCCESS) conditions.add("error_code is null");
        if (f.outcome() == Outcome.FAILURE) conditions.add("error_code is not null");
        if (f.from() != null) {
            conditions.add("created_at >= ?");
            args.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            conditions.add("created_at < ?");
            args.add(Timestamp.from(f.to()));
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        String direction = sort.endsWith(",asc") ? "asc" : "desc";

        long total = jdbc.queryForObject("select count(*) from ops.ai_call_logs" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<AiCallLogView> items = jdbc.query(
                SELECT + where + " order by created_at " + direction + ", id " + direction + " limit ? offset ?",
                ROW, pageArgs.toArray());
        return new PageImpl<>(items, PageRequest.of(page, size), total);
    }

    private static void equal(List<String> conditions, List<Object> args, String column, String value) {
        if (value != null) {
            conditions.add(column + " = ?");
            args.add(value);
        }
    }

    private static Instant seconds(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}