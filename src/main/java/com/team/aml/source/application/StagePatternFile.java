package com.team.aml.source.application;

import com.team.aml.source.domain.ParsedPatterns;
import com.team.aml.source.domain.PatternFileParser;
import com.team.aml.source.domain.PatternStagingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 패턴 파일을 파싱해 스테이징에 넣고 사건 수를 돌려준다. LoadIbmDataset 트랜잭션에 참여한다. */
@Service
public class StagePatternFile {

    private final PatternStagingRepository patterns;

    public StagePatternFile(PatternStagingRepository patterns) {
        this.patterns = patterns;
    }

    @Transactional
    public int execute(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // TRUNCATE 잠금으로 동시 실행을 줄 세운 뒤 번호를 읽어야 사건 ID가 겹치지 않는다.
            patterns.clear();
            ParsedPatterns parsed = PatternFileParser.parse(reader, patterns.lastEventNumber());
            patterns.saveAll(parsed.rows());
            return parsed.eventCount();
        } catch (IOException e) {
            throw new UncheckedIOException("패턴 파일을 읽을 수 없습니다", e);
        }
    }
}
