package com.team.aml.source.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 SOURCE_FILE_ALREADY_LOADED. 같은 파일 판단 기준은 파일명이 아니라 SHA-256이다. */
public class SourceFileAlreadyLoadedException extends DomainException {

    public SourceFileAlreadyLoadedException(String fileHash) {
        super("이미 적재된 파일입니다", Map.of("file_hash", fileHash));
    }
}
