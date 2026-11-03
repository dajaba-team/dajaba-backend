package com.team.aml.source.application;

import com.team.aml.source.domain.AccountFileFormatException;
import com.team.aml.source.domain.AccountLoadRepository;
import com.team.aml.source.domain.AccountLoadResult;
import com.team.aml.source.domain.AccountStagingCheck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 계좌 파일을 entities → accounts 순서로 적재한다. ImportIbmDataset 트랜잭션에 참여한다. */
@Service
public class LoadAccountFile {

    private static final String HEADER = "Bank Name,Bank ID,Account Number,Entity ID,Entity Name";

    private final AccountLoadRepository accounts;

    public LoadAccountFile(AccountLoadRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountLoadResult execute(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (!HEADER.equals(reader.readLine())) {
                throw new AccountFileFormatException("헤더가 다릅니다");
            }
            accounts.clearStaging();
            accounts.copyToStaging(reader);
            AccountStagingCheck check = accounts.checkStaging();
            check.requireValid();
            // accounts.entity_id가 필수 FK라 법인을 먼저 넣는다.
            long entities = accounts.insertEntities();
            long inserted = accounts.insertAccounts();
            return new AccountLoadResult(check.rowCount(), entities, inserted, check.unlistedCountryBanks());
        } catch (IOException e) {
            throw new UncheckedIOException("계좌 파일을 읽을 수 없습니다", e);
        }
    }
}
