package com.team.aml.source.application;

import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.source.domain.AccountFileFormatException;
import com.team.aml.source.domain.DatasetFiles;
import com.team.aml.source.domain.DatasetFilesMissingException;
import com.team.aml.source.domain.DatasetLoadSummary;
import com.team.aml.source.domain.DatasetVerificationException;
import com.team.aml.source.domain.PatternFileFormatException;
import com.team.aml.source.domain.TransactionFileFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * IBM HI-Small 적재 (SD 명세 §3). start()가 RUNNING을 커밋하고, run()이 적재 후 결과를 기록한다.
 * 컨트롤러는 start() 뒤 run()을 비동기로 부르고 202를 돌려준다 (SCRUM-70).
 */
@Service
public class LoadIbmDataset {

    private static final Logger log = LoggerFactory.getLogger(LoadIbmDataset.class);
    private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Seoul");

    private final ImportIbmDataset importer;
    private final DatasetLoadRecorder recorder;
    private final AmlProperties properties;

    public LoadIbmDataset(ImportIbmDataset importer, DatasetLoadRecorder recorder, AmlProperties properties) {
        this.importer = importer;
        this.recorder = recorder;
        this.properties = properties;
    }

    /** 파일이 없으면 batch_runs 행을 만들기 전에 거절한다. 적재 batch_date는 적재한 날(KST)이다. */
    public long start(long triggeredBy) {
        List<String> missing = DatasetFiles.missingIn(datasetDir());
        if (!missing.isEmpty()) {
            throw new DatasetFilesMissingException(missing);
        }
        return recorder.start(LocalDate.now(BATCH_ZONE), triggeredBy);
    }

    /** 실패해도 예외를 던지지 않는다. 결과는 batch_runs에 남고 API 2로 조회한다. */
    public void run(long batchRunId) {
        try {
            DatasetLoadSummary summary = importer.execute(datasetDir());
            recorder.succeed(batchRunId, summary.fileRows(), summary.insertedRows());
            // 실패 조건이 아닌 값은 기록만 한다. 건수만 남기고 금액·계좌는 남기지 않는다.
            log.info("적재 완료 batch_run_id={} rows={} pattern_matched={} pattern_unmatched={} pattern_multi_matched={}"
                            + " orphan_accounts={} amounts_rounded={}",
                    batchRunId, summary.insertedRows(), summary.patternMatch().matched(),
                    summary.patternMatch().unmatched(), summary.patternMatch().multiMatched(),
                    summary.orphanAccounts(), summary.amountsRounded());
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, failureMessage(e));
            // DB 예외 메시지에는 행 값이 들어갈 수 있어 클래스 이름만 남긴다.
            log.error("적재 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
        }
    }

    private Path datasetDir() {
        String dir = properties.datasetDir();
        return dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    /** 우리가 만든 예외는 파일 내용 없이 건수·이유만 담아 그대로 쓰고, 그 밖은 종류만 남긴다. */
    private static String failureMessage(RuntimeException e) {
        if (e instanceof DatasetVerificationException || e instanceof TransactionFileFormatException
                || e instanceof AccountFileFormatException || e instanceof PatternFileFormatException
                || e instanceof DomainException) {
            return e.getMessage();
        }
        return "적재 실패 (" + e.getClass().getSimpleName() + ")";
    }
}
