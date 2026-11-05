package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.DatasetExpectation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 서비스 DB에는 HI-Small만 들어간다 (안건 12). 테스트는 작은 파일에 맞는 기대값으로 바꿔 끼운다. */
@Configuration
public class DatasetExpectationConfig {

    @Bean
    public DatasetExpectation datasetExpectation() {
        return DatasetExpectation.HI_SMALL;
    }
}
