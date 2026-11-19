package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.CycleRule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 없이 확인하는 저장소 규칙. */
class RuleScanJdbcRepositoryTest {

    @Test
    void R04_고리_조회는_트랜잭션_밖에서_부르면_쿼리_전에_실패한다() {   // #148 리뷰 — 밖에서는 커서가 안 돼 결과 전체를 한 번에 받는다
        JdbcTemplate noDb = new JdbcTemplate();   // DataSource 없음 — 쿼리까지 가면 다른 예외가 난다
        RuleScanJdbcRepository repo = new RuleScanJdbcRepository(noDb, new ObjectMapper());
        LocalDateTime day = LocalDateTime.of(2022, 9, 5, 0, 0);

        assertThatThrownBy(() -> repo.forEachCycleGroup(day, day.plusDays(1), new CycleRule(96, 4), group -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션 안에서만");
    }
}
