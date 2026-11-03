package com.team.aml.source.domain;

/** 거래 파일 오류. 메시지가 error_message에 저장되므로 파일 내용은 넣지 않는다. */
public class TransactionFileFormatException extends RuntimeException {

    public TransactionFileFormatException(String reason) {
        super("거래 파일: " + reason);
    }
}
