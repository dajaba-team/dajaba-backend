package com.team.aml.source.domain;

import java.util.List;

public interface PatternStagingRepository {
    void clear();
    void saveAll(List<PatternRow> rows);
    PatternMatchStats matchStats();
    long lastEventNumber();
}
