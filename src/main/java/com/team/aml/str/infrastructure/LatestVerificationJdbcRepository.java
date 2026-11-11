package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.domain.LatestVerificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** 초안의 가장 최근 검증 결과(AI 응답 payload + revision_no·narrative_sha256 은 저장 열 값). 최근 = produced_at, 같으면 id. */
@Repository
public class LatestVerificationJdbcRepository implements LatestVerificationRepository {

    private static final String SQL = """
            SELECT payload::text, revision_no, narrative_sha256
            FROM ops.verification_results
            WHERE draft_id = ?
            ORDER BY produced_at DESC, id DESC
            LIMIT 1
            """;

    /** 같은 해시 중 최신 — ApprovalGate 와 같은 순서. 인덱스 verification_results_latest_idx(draft_id, narrative_sha256, …). */
    private static final String SQL_BY_HASH = """
            SELECT payload::text, revision_no, narrative_sha256
            FROM ops.verification_results
            WHERE draft_id = ? AND narrative_sha256 = ?
            ORDER BY produced_at DESC, id DESC
            LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LatestVerificationJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<JsonNode> findLatestByDraftId(long draftId) {
        return jdbc.query(SQL, this::readFirst, draftId);
    }

    @Override
    public Optional<JsonNode> findLatestByDraftIdAndHash(long draftId, String narrativeSha256) {
        return jdbc.query(SQL_BY_HASH, this::readFirst, draftId, narrativeSha256);
    }

    private Optional<JsonNode> readFirst(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return Optional.empty();
        }
        ObjectNode payload;
        try {
            payload = objectMapper.readValue(rs.getString(1), ObjectNode.class);
        } catch (IOException e) {
            throw new IllegalStateException("저장된 STR 검증 응답 JSON을 읽을 수 없습니다", e);
        }
        payload.put("revision_no", rs.getInt(2));
        payload.put("narrative_sha256", rs.getString(3));   // is_current 판정 — payload 값이 아니라 저장 열(서버가 계산한 해시)
        return Optional.of(payload);
    }
}
