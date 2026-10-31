package com.team.aml.source.domain;

import java.util.Arrays;
import java.util.Optional;

/** 패턴 파일의 세탁 유형 8종. 파일 표기(FAN-OUT)와 규약 표기(FAN_OUT)의 하이픈만 다르다. */
public enum PatternType {
    FAN_IN, FAN_OUT, GATHER_SCATTER, SCATTER_GATHER, CYCLE, STACK, BIPARTITE, RANDOM;

    public static Optional<PatternType> fromLabel(String label) {
        return Arrays.stream(values())
                .filter(type -> type.name().replace('_', '-').equals(label))
                .findFirst();
    }
}
