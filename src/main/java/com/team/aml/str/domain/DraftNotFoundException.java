package com.team.aml.str.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 DRAFT_NOT_FOUND */
public class DraftNotFoundException extends NotFoundException {

    public DraftNotFoundException(String draftNo) {
        super(
                "STR 초안을 찾을 수 없습니다: " + draftNo,
                Map.of("draft_no", draftNo)
        );
    }
}
