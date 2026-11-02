package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;

public class DraftNotVerifiedException extends DomainException {
    public DraftNotVerifiedException(String reason) {
        super("지금 본문을 통과한 검증이 없습니다: " + reason);
    }
}
