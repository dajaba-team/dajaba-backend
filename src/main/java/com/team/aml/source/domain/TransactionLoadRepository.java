package com.team.aml.source.domain;

import java.io.IOException;
import java.io.Reader;

public interface TransactionLoadRepository {
    void clearStaging();
    void copyToStaging(Reader rowsWithoutHeader) throws IOException;
    TransactionStagingCheck checkStaging();
    long insertFromStaging(long sourceFileId);
}
