package com.team.aml.source.domain;

/** COPY 직후 스테이징 검증 결과. 건수만 담는다. */
public record AccountStagingCheck(long rowCount, long missingRequired, long unknownEntityTypes, long entityNameConflicts) {

    public void requireValid() {
        if (rowCount == 0) {
            throw new AccountFileFormatException("데이터가 없습니다");
        }
        if (missingRequired > 0) {
            throw new AccountFileFormatException("필수값이 빠진 행 " + missingRequired + "건");
        }
        if (unknownEntityTypes > 0) {
            throw new AccountFileFormatException("법인 유형을 알 수 없는 행 " + unknownEntityTypes + "건");
        }
        if (entityNameConflicts > 0) {
            throw new AccountFileFormatException("이름이 여러 개인 법인 " + entityNameConflicts + "건");
        }
    }
}
