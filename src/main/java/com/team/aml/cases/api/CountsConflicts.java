package com.team.aml.cases.api;

import com.team.aml.cases.domain.CaseAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 케이스 변경 API 가 409 CONCURRENT_UPDATE 로 끝나면 {@code aml.cases.conflicts{action}}을 센다 (SCRUM-276).
 * version 을 받는 변경 API(POST·PUT)에 붙인다 — 빠지면 {@code CaseConflictCounterTest}가 잡는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CountsConflicts {

    /** 지표 action 태그 값. */
    CaseAction value();
}
