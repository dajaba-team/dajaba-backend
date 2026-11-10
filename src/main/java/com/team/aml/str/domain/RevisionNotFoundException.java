package com.team.aml.str.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 REVISION_NOT_FOUND — 그 초안에 없는 수정본 번호 (계약서 S4). */
public class RevisionNotFoundException extends NotFoundException {

    public RevisionNotFoundException(String draftNo, int revisionNo) {
        super("수정본을 찾을 수 없습니다: " + draftNo + " #" + revisionNo,
                Map.<String, Object>of("draft_no", draftNo, "revision_no", revisionNo));
    }
}
