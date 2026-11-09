package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.str.domain.LatestVerificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class LatestVerificationJdbcRepository
        implements LatestVerificationRepository {

    private static final String SQL = """
            SELECT payload::text
            FROM ops.verification_results
            WHERE draft_id = ?
            ORDER BY produced_at DESC, id DESC
            LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LatestVerificationJdbcRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<JsonNode> findLatestPayloadByDraftId(long draftId) {
        return jdbc.query(SQL, this::readFirst, draftId);
    }

    private Optional<JsonNode> readFirst(ResultSet rs)
            throws SQLException {

        if (!rs.next()) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    objectMapper.readTree(rs.getString(1))
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "저장된 STR 검증 응답 JSON을 읽을 수 없습니다", e
            );
        }
    }
}
