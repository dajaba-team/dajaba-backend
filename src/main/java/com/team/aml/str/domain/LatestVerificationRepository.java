package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

public interface LatestVerificationRepository {

    Optional<JsonNode> findLatestPayloadByDraftId(long draftId);
}
