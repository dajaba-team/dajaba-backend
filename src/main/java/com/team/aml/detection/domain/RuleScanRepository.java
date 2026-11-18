package com.team.aml.detection.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 룰 스캔의 집계·저장 (SD 명세 §5). 집계는 SQL, 판정은 FanInRule·FanOutRule 같은 domain 룰이 한다. */
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
    List<FanMinute> fanInMinutes(LocalDate date, FanInRule rule);

    /** R01 집계 — 그날 송금한 (계좌, 분) 마다 지금 창·1분 전 창의 수신 계좌 수. minDistinctReceivers 는 후보 거르기에만. */
    List<FanMinute> fanOutMinutes(LocalDate date, FanOutRule rule);

    /** R06 집계 — 그날 입금·출금이 있었던 (계좌, 분) 마다 지금 창·1분 전 창의 송신·수신 계좌 수. 하한·상한은 후보 거르기에만. */
    List<GatherScatterMinute> gatherScatterMinutes(LocalDate date, GatherScatterRule rule);

    /** R04 — 돌아오는 거래(→A)가 [closeFrom, closeTo) 에 있는 거래 단위 고리 전부. 각 고리는 자기 창(돌아오는 분 기준) 안. */
    List<CyclePath> cyclesClosing(LocalDateTime closeFrom, LocalDateTime closeTo, CycleRule rule);

    /**
     * R04 1분 전 창 판단용 — accounts 의 고리 중 [closeFrom, closeTo) 에 닫힌 것을 (계좌, 닫힌 분) 별 가장 늦은 첫 거래 시각으로만.
     * 경보에 담지 않는 과거 고리의 거래 조합은 가져오지 않는다.
     */
    List<CycleMark> cycleMarksClosing(LocalDateTime closeFrom, LocalDateTime closeTo, CycleRule rule,
                                      Collection<String> accounts);

    /** 계좌의 법인 (accounts 에 없으면 null). */
    String entityIdOf(String accountKey);

    /** [windowStart, windowEnd) 의 입금 근거(자기 계좌 제외) + 계좌의 법인. */
    FanEvidence fanInEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd);

    /** [windowStart, windowEnd) 의 송금 근거(자기 계좌 제외, 보낸 쪽 금액) + 계좌의 법인. */
    FanEvidence fanOutEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd);

    /** 경보 + alert_transactions 저장. 경보 id. */
    long insertAlert(NewAlert alert);

    /** 앱이 죽어 RUNNING 으로 남은 룰 스캔을 FAILED 로. 바꾼 행 수. */
    int failRunningScans(String message);
}
