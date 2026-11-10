package com.team.aml.str.api;

import com.team.aml.str.domain.StrSection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PUT /str-drafts/{draft_no}/revisions 본문 (계약서 S3) — 고친 단락만. 근거(cites·evidence_ids)·facts 는 받지 않는다
 * (보내도 모르는 칸이라 무시, fail-on-unknown-properties false). 없는 단락 이름은 JSON 읽기에서 400.
 */
public record ReviseStrDraftRequest(
        @NotNull @PositiveOrZero Long caseVersion,
        @NotEmpty List<@Valid @NotNull SectionEdit> sections) {

    public record SectionEdit(@NotNull StrSection section, @NotBlank @NfcMaxLength(5_000) String text) {}

    /** 같은 단락을 두 번 보내면 400 — details 키 sections_unique. */
    @AssertTrue(message = "같은 단락이 두 번 있습니다")
    public boolean isSectionsUnique() {
        if (sections == null) return true;
        return sections.stream().filter(s -> s != null && s.section() != null).map(SectionEdit::section)
                .distinct().count() == sections.stream().filter(s -> s != null && s.section() != null).count();
    }

    Map<StrSection, String> editedTexts() {
        Map<StrSection, String> texts = new LinkedHashMap<>();
        sections.forEach(s -> texts.put(s.section(), s.text()));
        return texts;
    }
}
