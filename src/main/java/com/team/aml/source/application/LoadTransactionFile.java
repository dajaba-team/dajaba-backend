package com.team.aml.source.application;

import com.team.aml.source.domain.SourceFile;
import com.team.aml.source.domain.SourceFileAlreadyLoadedException;
import com.team.aml.source.domain.SourceFileRepository;
import com.team.aml.source.domain.TransactionFileFormatException;
import com.team.aml.source.domain.TransactionLoadRepository;
import com.team.aml.source.domain.TransactionLoadResult;
import com.team.aml.source.domain.TransactionStagingCheck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 거래 파일을 transactions로 적재한다. 패턴은 먼저 스테이징돼 있어야 하며 LoadIbmDataset 트랜잭션에 참여한다. */
@Service
public class LoadTransactionFile {

    private static final String HEADER = "Timestamp,From Bank,Account,To Bank,Account,Amount Received,"
            + "Receiving Currency,Amount Paid,Payment Currency,Payment Format,Is Laundering";

    private final TransactionLoadRepository transactions;
    private final SourceFileRepository sourceFiles;

    public LoadTransactionFile(TransactionLoadRepository transactions, SourceFileRepository sourceFiles) {
        this.transactions = transactions;
        this.sourceFiles = sourceFiles;
    }

    @Transactional
    public TransactionLoadResult execute(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (!HEADER.equals(reader.readLine())) {
                throw new TransactionFileFormatException("헤더가 다릅니다");
            }
            String fileHash = sha256(file);
            transactions.clearStaging();
            if (sourceFiles.findByFileHash(fileHash).isPresent()) {
                throw new SourceFileAlreadyLoadedException(fileHash);
            }
            SourceFile sourceFile = sourceFiles.save(SourceFile.of(file.getFileName().toString(), fileHash));

            transactions.copyToStaging(reader);
            TransactionStagingCheck check = transactions.checkStaging();
            check.requireValid();
            long inserted = transactions.insertFromStaging(sourceFile.getId());
            return new TransactionLoadResult(sourceFile.getId(), check.rowCount(), inserted, check.amountsRounded());
        } catch (IOException e) {
            throw new UncheckedIOException("거래 파일을 읽을 수 없습니다", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest())) {
            MessageDigest digest = ((DigestInputStream) in).getMessageDigest();
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
