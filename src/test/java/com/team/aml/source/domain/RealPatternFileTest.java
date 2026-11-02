package com.team.aml.source.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 파일은 저장소에 없으므로 AML_DATASET_DIR을 지정한 PC에서만 돈다. CI는 건너뛴다. */
@EnabledIfEnvironmentVariable(named = "AML_DATASET_DIR", matches = ".+")
class RealPatternFileTest {

    @Test
    void 실제_파일은_370사건_3209거래_유형_8종이다() throws Exception {
        Path file = Path.of(System.getenv("AML_DATASET_DIR"), "HI-Small_Patterns.txt");

        ParsedPatterns parsed;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            parsed = PatternFileParser.parse(reader);
        }

        assertThat(parsed.eventCount()).isEqualTo(370);
        assertThat(parsed.rows()).hasSize(3209);

        Map<PatternType, Long> events = parsed.rows().stream()
                .collect(Collectors.toMap(PatternRow::patternId, PatternRow::patternType, (a, b) -> a))
                .values().stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(events).containsOnly(
                Map.entry(PatternType.CYCLE, 54L),
                Map.entry(PatternType.GATHER_SCATTER, 51L),
                Map.entry(PatternType.BIPARTITE, 49L),
                Map.entry(PatternType.FAN_OUT, 48L),
                Map.entry(PatternType.SCATTER_GATHER, 44L),
                Map.entry(PatternType.STACK, 43L),
                Map.entry(PatternType.RANDOM, 41L),
                Map.entry(PatternType.FAN_IN, 40L));
    }
}
