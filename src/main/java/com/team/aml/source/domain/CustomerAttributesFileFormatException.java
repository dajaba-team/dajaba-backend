package com.team.aml.source.domain;

/** KYC 파일(customer_attributes.csv) 오류. 메시지가 error_message에 저장되므로 파일 내용은 넣지 않는다. */
public class CustomerAttributesFileFormatException extends RuntimeException {

    public CustomerAttributesFileFormatException(String reason) {
        super("KYC 파일: " + reason);
    }
}
