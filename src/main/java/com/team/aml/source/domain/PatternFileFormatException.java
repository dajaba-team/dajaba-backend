package com.team.aml.source.domain;

/** 패턴 파일 구조 오류. 메시지가 error_message에 저장되므로 줄 내용은 넣지 않는다. */
public class PatternFileFormatException extends RuntimeException {

    private final int lineNumber;

    public PatternFileFormatException(int lineNumber, String reason) {
        super("패턴 파일 " + lineNumber + "번째 줄: " + reason);
        this.lineNumber = lineNumber;
    }

    public int getLineNumber() { return lineNumber; }
}
