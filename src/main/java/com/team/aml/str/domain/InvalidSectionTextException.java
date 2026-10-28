package com.team.aml.str.domain;
import com.team.aml.shared.domain.DomainException;

/** 단락 문장이 비었거나 5,000자를 넘을 때. 도메인 안전장치(API는 @Valid로 먼저 400). */
public class InvalidSectionTextException extends DomainException {

    public InvalidSectionTextException(StrSection section, String reason) {
        super(section + " 단락: " + reason);
    }
}
