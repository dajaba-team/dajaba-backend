package com.team.aml.source.domain;

/**
 * KYC 파일 행 수와 실제 삽입 수. 차이는 법인이 없거나 이미 들어 있어 건너뛴 행이다.
 * 파일이 없으면 {@link #SKIPPED}(둘 다 0, fileFound=false).
 */
public record CustomerAttributesLoadResult(boolean fileFound, long fileRows, long inserted) {

    public static final CustomerAttributesLoadResult SKIPPED = new CustomerAttributesLoadResult(false, 0, 0);

    public long skipped() {
        return fileRows - inserted;
    }
}
