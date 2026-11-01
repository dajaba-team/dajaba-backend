package com.team.aml.source.domain;

import java.util.Optional;

public interface SourceFileRepository {
    Optional<SourceFile> findByFileHash(String fileHash);
    SourceFile save(SourceFile sourceFile);
}
