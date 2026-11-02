package com.team.aml.source.application;

import com.team.aml.source.domain.AccountLoadRepository;
import com.team.aml.source.domain.DatasetExpectation;
import com.team.aml.source.domain.DatasetFiles;
import com.team.aml.source.domain.DatasetLoadSummary;
import com.team.aml.source.domain.DatasetRepository;
import com.team.aml.source.domain.DatasetStats;
import com.team.aml.source.domain.DatasetVerificationException;
import com.team.aml.source.domain.PatternMatchStats;
import com.team.aml.source.domain.PatternStagingRepository;
import com.team.aml.source.domain.TransactionLoadRepository;
import com.team.aml.source.domain.TransactionLoadResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

/**
 * 파일 3개를 한 트랜잭션으로 적재하고 검증한다 (SD 명세 §3 ②).
 * 하나라도 실패하면 원천·source_files가 전부 롤백되어 따로 비울 것이 없다.
 */
@Service
public class ImportIbmDataset {

    private final StagePatternFile stagePatternFile;
    private final LoadAccountFile loadAccountFile;
    private final LoadTransactionFile loadTransactionFile;
    private final PatternStagingRepository patterns;
    private final AccountLoadRepository accounts;
    private final TransactionLoadRepository transactions;
    private final DatasetRepository dataset;
    private final DatasetExpectation expectation;

    public ImportIbmDataset(StagePatternFile stagePatternFile, LoadAccountFile loadAccountFile,
                            LoadTransactionFile loadTransactionFile, PatternStagingRepository patterns,
                            AccountLoadRepository accounts, TransactionLoadRepository transactions,
                            DatasetRepository dataset, DatasetExpectation expectation) {
        this.stagePatternFile = stagePatternFile;
        this.loadAccountFile = loadAccountFile;
        this.loadTransactionFile = loadTransactionFile;
        this.patterns = patterns;
        this.accounts = accounts;
        this.transactions = transactions;
        this.dataset = dataset;
        this.expectation = expectation;
    }

    @Transactional
    public DatasetLoadSummary execute(Path dir) {
        // 거래 INSERT가 패턴 스테이징을 LEFT JOIN하고, 계좌가 법인을 FK로 가리켜서 이 순서다.
        stagePatternFile.execute(dir.resolve(DatasetFiles.PATTERNS));
        loadAccountFile.execute(dir.resolve(DatasetFiles.ACCOUNTS));
        TransactionLoadResult loaded = loadTransactionFile.execute(dir.resolve(DatasetFiles.TRANSACTIONS));

        PatternMatchStats match = patterns.matchStats();
        DatasetStats stats = dataset.collectStats();
        List<String> mismatches = expectation.mismatches(stats);
        if (!mismatches.isEmpty()) {
            throw new DatasetVerificationException(mismatches);
        }

        patterns.clear();
        accounts.clearStaging();
        transactions.clearStaging();
        return new DatasetLoadSummary(loaded.fileRows(), loaded.insertedRows(), match,
                stats.orphanAccounts(), loaded.amountsRounded());
    }
}
