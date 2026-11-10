package com.team.aml.detection.domain;

import com.team.aml.shared.domain.DomainException;

/** 409 DATASET_NOT_LOADED — 거래가 한 건도 없다. 적재는 한 트랜잭션이라 실패한 적재는 거래를 남기지 않는다. */
public class DatasetNotLoadedException extends DomainException {
    public DatasetNotLoadedException() {
        super("원천 데이터가 아직 적재되지 않았습니다");
    }
}
