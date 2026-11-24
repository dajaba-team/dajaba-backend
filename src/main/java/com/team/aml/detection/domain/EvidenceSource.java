package com.team.aml.detection.domain;

import java.util.Objects;

/**
 * 근거 거래 하나의 원본 위치 (SCRUM-415) — `source_files.file_name` · `transactions.source_row_number`.
 * 거래 ID 는 DB 적재 순서에 따라 바뀔 수 있어 원본 파일·행으로 근거를 가리킨다.
 */
public record EvidenceSource(String fileName, long sourceRowNumber) {
    public EvidenceSource {
        Objects.requireNonNull(fileName, "fileName");
    }
}
