package com.team.aml.str.domain;

/** 검증 결과 저장소. 추가만 — 고치기·지우기 메서드는 없다. 구현은 str/infrastructure (Spring Data). */
public interface VerificationResultRepository {

    VerificationResult save(VerificationResult result);
}
