package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.SourceFile;
import com.team.aml.source.domain.SourceFileRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceFileJpaRepository extends JpaRepository<SourceFile, Long>, SourceFileRepository {
}
