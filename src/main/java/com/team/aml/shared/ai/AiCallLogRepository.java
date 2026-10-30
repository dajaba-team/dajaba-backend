package com.team.aml.shared.ai;

import org.springframework.data.repository.Repository;

/** 추가 전용 — 수정·삭제 메서드를 노출하지 않는다. */
public interface AiCallLogRepository extends Repository<AiCallLog, Long> {
    AiCallLog save(AiCallLog log);
}
