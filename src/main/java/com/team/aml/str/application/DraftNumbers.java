package com.team.aml.str.application;

import com.team.aml.str.domain.DraftNotFoundException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 경로의 draft_no(DRF-000123) → draft_id. 형식이 틀리면 없는 초안과 똑같이 404 DRAFT_NOT_FOUND. */
final class DraftNumbers {

    private static final Pattern DRAFT_NO = Pattern.compile("DRF-(\\d{6,18})");

    private DraftNumbers() {}

    static long require(String draftNo) {
        Matcher m = draftNo == null ? null : DRAFT_NO.matcher(draftNo);
        if (m == null || !m.matches()) {
            throw new DraftNotFoundException(String.valueOf(draftNo));
        }
        return Long.parseLong(m.group(1));
    }
}
