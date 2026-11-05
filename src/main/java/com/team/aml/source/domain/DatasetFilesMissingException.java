package com.team.aml.source.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.List;
import java.util.Map;

/** 409 DATASET_FILES_MISSING. batch_runs 행을 만들기 전에 거절한다 (SD 명세 §3). */
public class DatasetFilesMissingException extends DomainException {

    public DatasetFilesMissingException(List<String> missing) {
        super("적재할 파일이 없습니다", Map.of("missing_files", missing));
    }
}
