package com.team.aml.source.domain;

/** COPY 직후 스테이징 검증 결과. 건수만 담는다. amountsRounded는 실패 조건이 아니라 보고값이다. */
public record TransactionStagingCheck(long rowCount, long missingRequired, long invalidBankIds, long invalidTimestamps,
                                      long invalidAmounts, long invalidLabels, long amountsRounded) {

    public void requireValid() {
        if (rowCount == 0) {
            throw new TransactionFileFormatException("데이터가 없습니다");
        }
        if (missingRequired > 0) {
            throw new TransactionFileFormatException("필수값이 빠진 행 " + missingRequired + "건");
        }
        if (invalidBankIds > 0) {
            throw new TransactionFileFormatException("은행 ID가 숫자가 아닌 행 " + invalidBankIds + "건");
        }
        if (invalidTimestamps > 0) {
            throw new TransactionFileFormatException("시각 형식이 잘못된 행 " + invalidTimestamps + "건");
        }
        if (invalidAmounts > 0) {
            throw new TransactionFileFormatException("금액 형식이 잘못된 행 " + invalidAmounts + "건");
        }
        if (invalidLabels > 0) {
            throw new TransactionFileFormatException("라벨 값이 잘못된 행 " + invalidLabels + "건");
        }
    }
}
