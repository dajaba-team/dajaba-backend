package com.team.aml.cases.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class ListCasesTest {
    @Test
    void 생성일_오름차순의_동률은_ID_내림차순이다() {
        assertThat(ListCases.sortOf("created_at,asc").toList()).containsExactly(
                Sort.Order.asc("createdAt"), Sort.Order.desc("id"));
    }
}
