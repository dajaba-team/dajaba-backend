package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.OpenedScore;
import com.team.aml.cases.domain.ScoreClassification;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.detection.application.MarkCaseOpened;
import com.team.aml.detection.domain.AlertForCaseOpening;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경보 한 건 → 케이스 한 건. <b>경보마다 새 트랜잭션(REQUIRES_NEW)</b>이라 한 건이 실패해도 그 건만 롤백된다
 * (#46 MarkCaseOpened 는 이미 표시된 경보면 409 로 트랜잭션째 롤백 — 배치 전체를 묶으면 앞에서 만든 케이스까지 되돌아간다).
 * OpenCasesFromAlerts 가 같은 클래스 안에서 부르면 프록시를 안 거쳐 트랜잭션이 안 열리므로 별도 빈으로 둔다.
 *
 * <p>순서: 케이스 번호(ops.case_no_seq) → Case.open(OPEN 이력 + 생성 당시 점수·정책 스냅샷) 저장 → MarkCaseOpened
 * (cases → detection 유스케이스). 스냅샷은 V013 — 목록 점수 정렬(SCRUM-236)도 이 점수로 한다.
 */
@Service
class OpenCaseForAlert {

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final MarkCaseOpened markCaseOpened;

    OpenCaseForAlert(CaseRepository cases, CaseHistoryRepository histories, MarkCaseOpened markCaseOpened) {
        this.cases = cases;
        this.histories = histories;
        this.markCaseOpened = markCaseOpened;
    }

    /**
     * @param grade 이 경보를 HIGH 로 판정한 정책 결과 — 그때 정책 버전·경계값을 함께 남긴다(SCRUM-237)
     * @return 만든 케이스 번호
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String execute(AlertForCaseOpening alert, ScoreClassification grade) {
        // 케이스 번호 연도는 경보 기준 시각의 연도 (V006 주석, 레퍼런스 8.7)
        String caseNo = DisplayNumber.caseNo(alert.referenceTs().getYear(), cases.nextCaseNoSeq());
        OpenedScore opened = OpenedScore.of(alert.scoreId(), alert.modelVersion(), alert.featureVersion(), grade);
        Case c = Case.open(caseNo, alert.alertId(), alert.accountKey(), alert.entityId(), opened, CurrentUser.SYSTEM_ID);
        Case saved = cases.save(c);
        histories.saveAll(saved.takePendingHistories());
        markCaseOpened.execute(alert.alertId());
        return caseNo;
    }
}
