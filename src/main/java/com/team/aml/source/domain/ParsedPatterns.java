package com.team.aml.source.domain;

import java.util.List;

public record ParsedPatterns(int eventCount, List<PatternRow> rows) {
}
