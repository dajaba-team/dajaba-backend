package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.NotificationRow;
import com.team.aml.cases.domain.NotificationRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 업무 알림 쿼리 (SCRUM-355). 받는 사람 규칙은 {@link NotificationRows} Javadoc 그대로, 목록과 건수가 같은 WHERE 를 쓴다.
 * comment 칸은 select 하지 않는다. 인덱스는 지금 것 그대로 — created_at 이 앞인 인덱스가 없어 since 조건은 훑는다
 * (설계 6.5, 느려지면 인덱스 추가). 승인자 제외의 not exists 는 case_id 가 앞인 인덱스로 그 케이스 행만 본다.
 */
@Repository
class NotificationJdbcRows implements NotificationRows {

    private static final String FROM = """
             from ops.case_history h
             join ops.cases c on c.id = h.case_id
            where h.created_at > :since
              and h.created_at <= :until
              and h.created_by <> :me
            """;

    private static final String INVESTIGATOR = """
              and ((h.action = 'ASSIGN' and h.to_assignee_id = :me)
                or (h.action = 'REASSIGN' and (h.to_assignee_id = :me or h.from_assignee_id = :me))
                or (h.action in ('APPROVE', 'REJECT', 'REPORT') and c.assignee_id = :me))
            """;

    /**
     * 지금 담당이거나 받은·넘긴 사람으로 남은 이력이 있으면 제외. WasCaseAssignee(to 만)보다 넓다 — V010 전 배정은 받은 사람
     * 칸이 비어 있어, 그 뒤 넘긴 사람은 from 에만 남는다(추가 검토 1). from 은 인덱스가 없지만 case_id 로 그 케이스 행만 본다.
     */
    private static final String APPROVER = """
              and h.action = 'SUBMIT_FOR_APPROVAL'
              and c.assignee_id is distinct from :me
              and not exists (select 1 from ops.case_history x
                               where x.case_id = h.case_id
                                 and (x.to_assignee_id = :me or x.from_assignee_id = :me))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    NotificationJdbcRows(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NotificationRow> find(long me, Audience audience, Instant since, Instant until, int limit) {
        String sql = """
                select h.action, coalesce(h.to_assignee_id = :me, false) as received_by_me, c.case_no,
                       c.current_draft_id, h.reason_code, h.created_by, h.created_at
                """ + FROM + where(audience) + " order by h.created_at desc, h.id desc limit :limit";
        return jdbc.query(sql, params(me, since, until).addValue("limit", limit), (rs, i) -> new NotificationRow(
                CaseHistoryAction.valueOf(rs.getString("action")), rs.getBoolean("received_by_me"),
                rs.getString("case_no"), rs.getObject("current_draft_id", Long.class), rs.getString("reason_code"),
                rs.getLong("created_by"), rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    @Override
    public int count(long me, Audience audience, Instant since, Instant until, int cap) {
        String sql = "select count(*) from (select 1 " + FROM + where(audience) + " limit :cap) t";
        Integer n = jdbc.queryForObject(sql, params(me, since, until).addValue("cap", cap), Integer.class);
        return n == null ? 0 : n;
    }

    private static String where(Audience audience) {
        return switch (audience) {
            case INVESTIGATOR -> INVESTIGATOR;
            case APPROVER -> APPROVER;
        };
    }

    /**
     * 시각은 마이크로초로 자른다 — DB 가 마이크로초라 드라이버가 나노초를 반올림하면 since 바로 뒤 행이 빠진다(추가 검토 1).
     * 자르면 경계가 아래로만 움직여 빠지는 행이 없다.
     */
    private static MapSqlParameterSource params(long me, Instant since, Instant until) {
        return new MapSqlParameterSource().addValue("me", me)
                .addValue("since", utc(since))
                .addValue("until", utc(until));
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
