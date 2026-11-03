package com.team.aml.source.domain;

import java.io.IOException;
import java.io.Reader;

public interface AccountLoadRepository {
    void clearStaging();
    void copyToStaging(Reader rowsWithoutHeader) throws IOException;
    AccountStagingCheck checkStaging();
    long insertEntities();
    long insertAccounts();
}
