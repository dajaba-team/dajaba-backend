package com.team.aml.source.domain;

public interface DatasetRepository {

    /** 적재 시작을 한 번에 하나만 판단하도록 트랜잭션 잠금을 건다. */
    void lockLoadStart();

    boolean hasTransactions();

    /** 날짜와 상관없이 실행 중인 적재가 있나 (미결 4: 적재는 실행 1개만). */
    boolean hasRunningLoad();

    /** 실행 중으로 남은 적재를 FAILED로 바꾸고 바꾼 행 수를 돌려준다. */
    int failRunningLoads(String message);

    DatasetStats collectStats();
}
