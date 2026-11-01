package com.team.aml.shared.ai;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 호출자의 롤백과 무관하게 시도 기록을 남긴다. 커밋 실패도 호출자가 처리한다. */
@Component
public class AiCallLogWriter {

    private final AiCallLogRepository repository;

    public AiCallLogWriter(AiCallLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AiCallLog log) {
        repository.save(log);
    }
}
