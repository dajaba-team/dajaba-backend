package com.team.aml.source.domain;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceEntityMappingTest {

    @Test
    void 거래_엔티티는_정답_라벨을_매핑하지_않는다() {
        List<String> fields = Arrays.stream(Transaction.class.getDeclaredFields())
                .map(Field::getName).toList();

        assertThat(fields).doesNotContain("isLaundering", "laundering", "patternType", "patternId");
    }

    @Test
    void 원천_엔티티는_읽기_전용이고_setter가_없다() {
        for (Class<?> type : List.of(Transaction.class, Account.class, LegalEntity.class, CustomerAttributes.class)) {
            assertThat(type.isAnnotationPresent(Immutable.class)).isTrue();
            assertThat(Arrays.stream(type.getDeclaredMethods()).map(Method::getName))
                    .noneMatch(name -> name.startsWith("set"));
        }
    }
}
