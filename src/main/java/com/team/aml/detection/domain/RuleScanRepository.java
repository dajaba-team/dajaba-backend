package com.team.aml.detection.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 룰 스캔의 집계·저장 (SD 명세 §5). 집계는 SQL, 판정은 FanInRule 같은 domain 룰이 한다. */
public interface RuleScanRepository {

    /** 같은 날짜 스캔 시작을 줄 세운다. 트랜잭션이 끝나면 풀린다. */
    void lockScanStart();

    /** 거래가 한 건도 없으면 empty (적재 전). */
    Optional<DataRange> dataRange();

    List<ActiveRule> findActiveRules();

    /** 그날 거래 수 — batch_runs.rows_in. */
    int countTransactionsOn(LocalDate date);

    /**
     * R02 집계 — 그날 입금이 있었던 (계좌, 분) 마다 지금 창·1분 전 창(길이 rule.windowHours)의 송금 계좌 수.
     * minDistinctSenders 는 후보 계좌를 미리 거르는 데만 쓴다(하루 범위에서도 그 수가 안 되면 어느 분에도 참이 될 수 없다).
     */
    List<FanInMinute> fanInMinutes(LocalDate date, FanInRule rule);

    /** [windowStart, windowEnd) 의 입금 근거(자기 계좌 제외) + 계좌의 법인. */
    FanInEvidence fanInEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd);

    /** 경보 + alert_transactions 저장. 경보 id. */
    long insertAlert(NewAlert alert);

    /** 앱이 죽어 RUNNING 으로 남은 룰 스캔을 FAILED 로. 바꾼 행 수. */
    int failRunningScans(String message);
}
