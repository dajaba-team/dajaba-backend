package com.team.aml.shared.audit;

import com.team.aml.shared.domain.DisplayNumber;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 접근 기록 조회 (SCRUM-168). 읽기 전용 — AccessLogRepository 는 추가 전용(save)만 그대로 둔다 (원칙 3).
 * 엔티티·저장소 대신 JdbcTemplate (AiCallLogQuery 와 같음). 화면 번호는 SQL 한 번에 테이블만 읽어 바꾼다 —
 * 기능 클래스에는 기대지 않는다(shared 는 기능을 모름). STR 초안은 번호 칸이 없어 DisplayNumber.draftNo(id).
 * ip 는 SELECT 하지 않는다. 필터 없이 부르면 created_at 전체 정렬 — MVP 행 수가 적어 인덱스는 안 더함(08_decisions 11/12).
 */
@Repository
public class AccessLogQuery {

    /**
     * 전부 선택·AND. from 포함·to 제외. targetNo 는 api 쪽이 targetType 에 맞는 화면 번호 형식인지 이미 검사한 값
     * (STR_DRAFT 면 DRF- 뒤 숫자 6~18자리). 형식은 맞는데 없는 번호·아이디는 0건.
     */
    public record Filter(String loginId, AccessLogAction action, TargetType targetType, String targetNo,
                         Instant from, Instant to) {}

    private static final String SELECT = """
            select a.id, u.login_id, u.display_name, a.login_id_attempted, a.action, a.target_type, a.target_id,
                   c.case_no, al.alert_no, tu.login_id as target_login_id, a.created_at
            from ops.access_logs a
            left join ops.users u on u.id = a.user_id
            left join ops.cases c on a.target_type = 'CASE' and c.id = a.target_id
            left join ops.alerts al on a.target_type = 'ALERT' and al.id = a.target_id
            left join ops.users tu on a.target_type = 'USER' and tu.id = a.target_id""";

    private static final RowMapper<AccessLogView> ROW = (rs, rowNum) -> new AccessLogView(
            rs.getLong("id"), rs.getString("login_id"), rs.getString("display_name"),
            rs.getString("login_id_attempted"), rs.getString("action"), rs.getString("target_type"),
            targetNo(rs), seconds(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public AccessLogQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** sort 는 컨트롤러가 "created_at,desc" 형식을 검사한 값이다. */
    public Page<AccessLogView> search(Filter f, int page, int size, String sort) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (f.loginId() != null) {
            conditions.add("a.user_id = (select id from ops.users where login_id = ?)");   // LOGIN_FAIL(user_id null)은 안 잡힘
            args.add(f.loginId());
        }
        if (f.action() != null) {
            conditions.add("a.action = ?");
            args.add(f.action().name());
        }
        if (f.targetType() != null) {
            conditions.add("a.target_type = ?");
            args.add(f.targetType().name());
        }
        if (f.targetNo() != null) {
            conditions.add("a.target_id = " + targetIdSql(f.targetType()));
            args.add(f.targetType() == TargetType.STR_DRAFT ? Long.parseLong(f.targetNo().substring(4)) : f.targetNo());
        }
        if (f.from() != null) {
            conditions.add("a.created_at >= ?");
            args.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            conditions.add("a.created_at < ?");
            args.add(Timestamp.from(f.to()));
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        String direction = sort.endsWith(",asc") ? "asc" : "desc";

        long total = jdbc.queryForObject("select count(*) from ops.access_logs a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<AccessLogView> items = jdbc.query(
                SELECT + where + " order by a.created_at " + direction + ", a.id " + direction + " limit ? offset ?",
                ROW, pageArgs.toArray());
        return new PageImpl<>(items, PageRequest.of(page, size), total);
    }

    /** 화면 번호 → DB 숫자 id. 없는 번호면 하위 질의가 null 이라 0건. */
    private static String targetIdSql(TargetType type) {
        return switch (type) {
            case CASE -> "(select id from ops.cases where case_no = ?)";
            case ALERT -> "(select id from ops.alerts where alert_no = ?)";
            case USER -> "(select id from ops.users where login_id = ?)";
            case STR_DRAFT -> "?";
        };
    }

    /** DB 숫자 target_id → 화면 번호. 대상 행이 없으면(FK 없음) null. */
    private static String targetNo(ResultSet rs) throws SQLException {
        String type = rs.getString("target_type");
        if (type == null) return null;
        return switch (TargetType.valueOf(type)) {
            case CASE -> rs.getString("case_no");
            case ALERT -> rs.getString("alert_no");
            case USER -> rs.getString("target_login_id");
            case STR_DRAFT -> rs.getObject("target_id") == null ? null : DisplayNumber.draftNo(rs.getLong("target_id"));
        };
    }

    private static Instant seconds(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
