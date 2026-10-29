package com.team.aml.str.domain;

import java.util.List;

/** 본문 단락 하나: 단락 이름, 문장, 근거 거래 번호(transaction_id, bigint). */
public record NarrativeSection(StrSection section, String text, List<Long> evidenceIds) {

    public NarrativeSection {
        evidenceIds = List.copyOf(evidenceIds);   // 밖에서 목록을 바꿔도 안 바뀌게, 근거 번호는 아무도 못 바꾼다는 원칙
    }
}