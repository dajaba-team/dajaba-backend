package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.detection.domain.AlertRepository;
import com.team.aml.detection.domain.CaseAlreadyOpenedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경보에 케이스가 생겼다고 표시한다 (SD 명세 §10). cases 케이스 생성 트랜잭션에 참여한다(MANDATORY) —
 * 케이스 INSERT 와 같이 성공·실패해야 해서, 트랜잭션 밖에서 부르면 IllegalTransactionStateException.
 * 조건부 UPDATE 라 같은 경보를 두 배치가 동시에 표시해도 하나만 성공한다.
 */
@Service
public class MarkCaseOpened {

    private final AlertRepository alerts;

    public MarkCaseOpened(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(long alertId) {
        if (alerts.markCaseOpened(alertId)) {
            return;
        }
        if (!alerts.exists(alertId)) {
            throw new AlertNotFoundException(alertId);
        }
        throw new CaseAlreadyOpenedException(alertId);
    }
}
