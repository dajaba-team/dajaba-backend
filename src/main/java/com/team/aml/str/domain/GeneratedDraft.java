package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * AI 초안 응답(규약 5.2)에서 초안을 만드는 데 필요한 값만 모은 것. JSON 을 푸는 일은 application(AiDraftResponseReader)이 한다.
 * 값이 맞는지(빈 값·단락 세트·글자 수)는 StrDraft.create 가 검사한다.
 *
 * @param generationId     AI 가 생성 1회마다 발급 (안건 23)
 * @param generatorVersion generation_meta.prompt_version (11/3 결정)
 * @param producedAt       AI 가 UTC Z 로 준 생성 시각
 * @param payload          AI 응답 전체. facts 는 여기서 꺼낸다(DB 는 generated column)
 * @param narrative        AI 원문 단락(cites·evidence_ids 포함). 수정본 1번이 된다
 */
public record GeneratedDraft(String generationId, String generatorVersion, Instant producedAt,
                             JsonNode payload, List<NarrativeSection> narrative) {

    public GeneratedDraft {
        Objects.requireNonNull(payload, "payload");
        narrative = List.copyOf(narrative);
    }
}
