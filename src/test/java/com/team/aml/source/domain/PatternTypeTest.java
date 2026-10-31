package com.team.aml.source.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PatternTypeTest {

    @ParameterizedTest
    @EnumSource(PatternType.class)
    void 파일_표기를_유형으로_바꾼다(PatternType type) {
        assertThat(PatternType.fromLabel(type.name().replace('_', '-'))).contains(type);
    }

    @Test
    void 정의되지_않은_표기는_없다고_답한다() {
        assertThat(PatternType.fromLabel("UNKNOWN")).isEmpty();
        assertThat(PatternType.fromLabel("FAN_OUT")).isEmpty();
    }
}
