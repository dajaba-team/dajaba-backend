package com.team.aml.source.domain;

/** 계좌 파일 오류. 메시지가 error_message에 저장되므로 파일 내용은 넣지 않는다. */
public class AccountFileFormatException extends RuntimeException {

    public AccountFileFormatException(String reason) {
        super("계좌 파일: " + reason);
    }
}
