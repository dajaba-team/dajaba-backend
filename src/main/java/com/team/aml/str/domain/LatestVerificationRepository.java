package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** 저장된 검증 결과(AI 응답 payload) 읽기. 구현은 str/infrastructure (JDBC). */
public interface LatestVerificationRepository {

    /** 초안의 가장 최근 검증 (S2 latest_verification). */
    Optional<JsonNode> findLatestByDraftId(long draftId);

    /** 이 본문 해시를 검사한 가장 최근 검증 — 그 응답의 narrative_refs 가 지금 본문의 근거가 된다 (CurrentNarrative). */
    Optional<JsonNode> findLatestByDraftIdAndHash(long draftId, String narrativeSha256);
}
