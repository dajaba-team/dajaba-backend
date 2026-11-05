package com.team.aml.source.domain;

import java.util.List;

/** 적재 검증값 불일치. 적재 트랜잭션을 롤백하고 메시지는 error_message에 남는다. */
public class DatasetVerificationException extends RuntimeException {

    public DatasetVerificationException(List<String> mismatches) {
        super("검증값 불일치: " + String.join(", ", mismatches));
    }
}
