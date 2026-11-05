package com.team.aml.str.domain;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 본문 단락 하나: 단락 이름, 문장, 근거 거래 번호(transaction_id, bigint).
 * str_revisions 의 jsonb 에는 {"section","text","evidence_ids"} 로 저장한다 — 규약 0절 snake_case, Hibernate 의 ObjectMapper 설정과 상관없이.
 **/
public record NarrativeSection(StrSection section, String text,
                               @JsonProperty("evidence_ids")
                               List<Long> evidenceIds) {

    public NarrativeSection {
        evidenceIds = List.copyOf(evidenceIds);   // 밖에서 목록을 바꿔도 안 바뀌게, 근거 번호는 아무도 못 바꾼다는 원칙
    }
}
