package com.team.aml.shared.audit;

/** 추가 전용 — 수정·삭제 메서드를 노출하지 않는다. */
public interface AccessLogRepository extends org.springframework.data.repository.Repository<AccessLog, Long> {
    AccessLog save(AccessLog log);
}
