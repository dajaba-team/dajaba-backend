package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.ai.AiDtos.AlertPayload;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 규약 §2 경보 JSON 을 alerts a · rules r 행에서 만든다. 초안 요청(AlertJdbcRepository.findForDraft)과
 * 점수 요청(AlertScoreJdbcRepository)이 같은 모양을 보내도록 한 곳에 둔다.
 * alert_id 는 alert_no, rule_version 은 문자열, 데이터 시각은 초까지, created_at 은 UTC Z 초까지.
 */
final class AlertPayloadRows {

    /** select 에 넣는 칸. rule_facts 는 text 로 읽고 부르는 쪽이 JSON 으로 바꾼다. */
    static final String COLUMNS = """
            a.alert_no, a.account_key, a.entity_id, r.rule_id, r.version, r.typology_hint,
                   a.window_start, a.window_end, a.reference_ts, a.rule_facts::text as rule_facts, a.created_at""";

    /** 데이터 시각은 규약 예시처럼 초까지. LocalDateTime.toString 은 초가 0이면 잘라서 쓰지 않는다. */
    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AlertPayloadRows() {}

    static AlertPayload payload(ResultSet rs, List<Long> transactionIds, JsonNode ruleFacts) throws SQLException {
        return new AlertPayload(rs.getString("alert_no"), rs.getString("account_key"), rs.getString("entity_id"),
                rs.getString("rule_id"), String.valueOf(rs.getInt("version")), rs.getString("typology_hint"),
                DATA_TIME.format(rs.getObject("window_start", LocalDateTime.class)),
                DATA_TIME.format(rs.getObject("window_end", LocalDateTime.class)),
                DATA_TIME.format(rs.getObject("reference_ts", LocalDateTime.class)),
                transactionIds, ruleFacts,
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
                        .truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
