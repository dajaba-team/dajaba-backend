package com.team.aml.str.application;

import com.team.aml.str.domain.LatestVerificationRepository;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeRefs;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 지금 본문 + 지금 근거. 근거(cites·evidence_ids)는 지금 본문 해시와 같은 narrative_sha256 의 최신 검증에
 * narrative_refs 가 있으면 그 값, 없으면 수정본 값 (진희 11/10, 규약 §5.3 변경 요청 예정).
 * S2 narrative, S5 검증 요청에 같이 쓴다 — 두 곳이 다른 근거를 보면 안 된다. S4 이력은 수정본 저장값 그대로.
 * 부르는 쪽 트랜잭션 안에서 쓴다.
 */
@Component
class CurrentNarrative {

    private final LatestVerificationRepository verifications;

    CurrentNarrative(LatestVerificationRepository verifications) {
        this.verifications = verifications;
    }

    List<NarrativeSection> of(StrDraft draft) {
        List<NarrativeSection> body = draft.currentBody();
        return verifications.findLatestByDraftIdAndHash(draft.getId(), NarrativeHash.of(body))
                .map(v -> NarrativeRefs.apply(body, v.get("narrative_refs")))
                .orElse(body);
    }
}
