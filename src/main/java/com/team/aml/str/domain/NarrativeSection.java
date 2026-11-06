package com.team.aml.str.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 본문 단락 하나: 단락 이름, 문장, 근거 facts 키(cites), 근거 거래 번호(evidence_ids, transaction_id bigint).
 * str_revisions 의 jsonb 에는 {"section","text","cites","evidence_ids"} 로 저장한다 — 규약 0절 snake_case, Hibernate 의 ObjectMapper 설정과 상관없이.
 * cites = 이 문장이 근거로 쓴 facts 키(예: "period", "inbound.count"). AI 초안 응답에 오고, 검증 요청에 그대로 다시 보낸다
 * (규약 v0.4 8번 — 빠지면 근거 검사가 CODE_FAILED). 해시(NarrativeHash)는 section·text 만 써서 cites 는 해시를 바꾸지 않는다.
 **/
public record NarrativeSection(StrSection section, String text,
                               List<String> cites,
                               @JsonProperty("evidence_ids")
                               List<Long> evidenceIds) {

    public NarrativeSection {
        cites = cites == null ? List.of() : List.copyOf(cites);   // cites 칸 전에 저장된 수정본은 키가 없다 → 빈 목록
        evidenceIds = List.copyOf(evidenceIds);   // 밖에서 목록을 바꿔도 안 바뀌게, 근거 번호는 아무도 못 바꾼다는 원칙
    }

    /** cites 없이 만든다(빈 목록). */
    public NarrativeSection(StrSection section, String text, List<Long> evidenceIds) {
        this(section, text, List.of(), evidenceIds);
    }

    /** 문장만 바꾼 새 단락. 근거(cites·evidence_ids)는 그대로 — 다시 계산은 검증 응답 narrative_refs 로 (W4). */
    public NarrativeSection withText(String newText) {
        return new NarrativeSection(section, newText, cites, evidenceIds);
    }
}
