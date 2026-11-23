package com.team.aml.str.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/** aml.str.pdf.storage 는 filesystem 또는 minio 만 허용한다. */
@Configuration
public class StrPdfStorageValidationConfig {

    private static final Set<String> ALLOWED = Set.of("filesystem", "minio");

    public StrPdfStorageValidationConfig(@Value("${aml.str.pdf.storage:filesystem}") String storage) {
        if (storage == null || !ALLOWED.contains(storage)) {
            throw new IllegalStateException(
                    "aml.str.pdf.storage 는 filesystem 또는 minio 이어야 합니다 (현재 값: " + storage + ")");
        }
    }
}