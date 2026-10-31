package com.team.aml.source.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 원본 파일 한 건. 같은 파일 판단 기준은 파일명이 아니라 SHA-256이다 (안건 1). */
@Entity
@Table(name = "source_files")
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_hash", nullable = false, unique = true, length = 64)
    private String fileHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected SourceFile() {}

    public static SourceFile of(String fileName, String fileHash) {
        SourceFile file = new SourceFile();
        file.fileName = fileName;
        file.fileHash = fileHash;
        return file;
    }

    public Long getId() { return id; }
    public String getFileName() { return fileName; }
    public String getFileHash() { return fileHash; }
    public Instant getCreatedAt() { return createdAt; }
}
