package com.team.aml.source.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** IBM HI-Small 파일 3개. 경로는 AML_DATASET_DIR (SD 명세 §3). */
public final class DatasetFiles {

    public static final String TRANSACTIONS = "HI-Small_Trans.csv";
    public static final String ACCOUNTS = "HI-Small_accounts.csv";
    public static final String PATTERNS = "HI-Small_Patterns.txt";

    private static final List<String> ALL = List.of(TRANSACTIONS, ACCOUNTS, PATTERNS);

    private DatasetFiles() {}

    /** 없는 파일 이름. 폴더가 지정되지 않았으면 전부 없는 것으로 본다. */
    public static List<String> missingIn(Path dir) {
        if (dir == null) {
            return ALL;
        }
        return ALL.stream().filter(name -> !Files.isRegularFile(dir.resolve(name))).toList();
    }
}
