package com.team.aml.source.domain;

/**
 * COPY 직후 스테이징 검증 결과. 건수만 담는다.
 * unlistedCountryBanks는 실패 조건이 아니라 보고값이다. "{X} Bank #n" 형식인데 국가 목록에 없는 은행 수다.
 */
public record AccountStagingCheck(long rowCount, long missingRequired, long invalidBankIds,
                                  long unknownEntityTypes, long entityNameConflicts, long accountOwnerConflicts,
                                  long bankNameConflicts, long existingConflicts, long unlistedCountryBanks) {

    public void requireValid() {
        if (rowCount == 0) {
            throw new AccountFileFormatException("데이터가 없습니다");
        }
        if (missingRequired > 0) {
            throw new AccountFileFormatException("필수값이 빠진 행 " + missingRequired + "건");
        }
        if (invalidBankIds > 0) {
            throw new AccountFileFormatException("은행 ID가 숫자가 아닌 행 " + invalidBankIds + "건");
        }
        if (unknownEntityTypes > 0) {
            throw new AccountFileFormatException("법인 유형을 알 수 없는 행 " + unknownEntityTypes + "건");
        }
        if (entityNameConflicts > 0) {
            throw new AccountFileFormatException("이름이 여러 개인 법인 " + entityNameConflicts + "건");
        }
        if (accountOwnerConflicts > 0) {
            throw new AccountFileFormatException("소유자가 여러 명인 계좌 " + accountOwnerConflicts + "건");
        }
        if (bankNameConflicts > 0) {
            throw new AccountFileFormatException("이름이 여러 개인 은행 " + bankNameConflicts + "건");
        }
        if (existingConflicts > 0) {
            throw new AccountFileFormatException("이미 적재된 계좌·은행과 값이 다른 행 " + existingConflicts + "건");
        }
    }
}
