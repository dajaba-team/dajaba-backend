package com.team.aml.str.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * POST /str-drafts/{draft_no}/submit 본문 (계약서 S6, 필드 이름 11/4 확정).
 * 없거나 범위 밖이면 400 INVALID_REQUEST — 도메인(StrDraft.recordBranchSuspicion)도 같은 규칙으로 한 번 더 막는다.
 *
 * @param verificationId       화면이 본 검증 번호 (verification_results.verification_id varchar(64))
 * @param suspicionTypes       의심 유형 코드(서식 VI) 1개 이상, 코드마다 16자 이하 (V008 varchar(16)[])
 * @param suspicionLevelBranch 지점용 의심 정도 1~5, 기본값 없음
 */
public record SubmitStrDraftRequest(
        @NotNull @PositiveOrZero Long caseVersion,
        @NotBlank @Size(max = 64) String verificationId,
        @NotEmpty List<@NotBlank @Size(max = 16) String> suspicionTypes,
        @NotNull @Min(1) @Max(5) Integer suspicionLevelBranch) {

    /** 같은 유형을 두 번 보내면 400 — details 키 suspicion_types_unique. */
    @AssertTrue(message = "같은 의심 유형이 두 번 있습니다")
    public boolean isSuspicionTypesUnique() {
        if (suspicionTypes == null) return true;
        List<String> present = suspicionTypes.stream().filter(Objects::nonNull).toList();
        return present.stream().distinct().count() == present.size();
    }
}
