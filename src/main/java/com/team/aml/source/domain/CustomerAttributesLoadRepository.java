package com.team.aml.source.domain;

import java.io.IOException;
import java.io.Reader;

public interface CustomerAttributesLoadRepository {

    /** 헤더를 뺀 CSV 행을 넣는다. 법인이 없거나 이미 있는 entity_id는 건너뛴다. 반환: (파일 행 수, 삽입 수). */
    CustomerAttributesLoadResult load(Reader rowsWithoutHeader) throws IOException;
}
