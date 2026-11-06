package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.audit.TargetType;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 케이스 상세 (CASES 명세 API-02, 계약서 C2). 조회 3역할, <b>열람 기록을 남긴다</b> (SCRUM-101, 03_shared §9).
 * 열람 기록이 조회 트랜잭션에 참여하므로 readOnly 가 아니다 — 커넥션 하나, 조회가 실패하면 기록도 롤백.
 * 순서: 케이스 조회(없으면 404, 기록 안 함) → accessLog.view(CASE) → 이력·담당자·버튼 계산.
 *
 * <p>이번에 채우는 것: 케이스 칸·담당자·최근 이력 20건·available_actions.
 * 아직 없는 것(후속): alert·score·latest_score(detection 공개 조회 필요), aggregates·latest_summary·investigator_notes
 * (API-08·12·05), current_draft 본문(str 조합 계층, 노션 미결 17). 컨트롤러가 그 칸은 null 로 내보낸다.
 */
@Service
public class GetCaseDetail {

    public record Result(String caseNo, CaseStatus status, String accountKey, String entityId, UserRef assignee,
                         RejectionReasonCode rejectionReasonCode, CloseReason closeReason, Long currentDraftId,
                         long version, Instant createdAt, Instant updatedAt,
                         List<History> history, List<CaseAction> availableActions) {
        public Result {
            history = history == null ? List.of() : List.copyOf(history);
            availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
        }
    }

    /** 이력 한 줄 (명세 API-11 모양). createdBy 는 login_id. 최신이 먼저. */
    public record History(CaseHistoryAction action, CaseStatus fromStatus, CaseStatus toStatus, String reasonCode,
                          String comment, String createdBy, Instant createdAt) {
    }

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;
    private final UserRefs userRefs;
    private final AccessLogWriter accessLog;
    private final CurrentUser currentUser;

    public GetCaseDetail(CaseRepository cases, CaseHistoryRepository histories, UserRefs userRefs,
                         AccessLogWriter accessLog, CurrentUser currentUser) {
        this.cases = cases;
        this.histories = histories;
        this.userRefs = userRefs;
        this.accessLog = accessLog;
        this.currentUser = currentUser;
    }

    @Transactional
    public Result execute(String caseNo) {
        AppUserDetails me = currentUser.require();
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        accessLog.view(TargetType.CASE, c.getId());

        List<CaseHistory> recent = histories.findTop20ByCaseIdOrderByIdDesc(c.getId());
        List<Long> people = new ArrayList<>(recent.stream().map(CaseHistory::getCreatedBy).toList());
        people.add(c.getAssigneeId());
        Map<Long, UserRef> refs = userRefs.of(people);

        List<History> history = recent.stream().map(h -> new History(h.getAction(), h.getFromStatus(), h.getToStatus(),
                h.getReasonCode(), h.getComment(), loginIdOf(refs.get(h.getCreatedBy())),
                h.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))).toList();
        List<CaseAction> actions = CaseAction.availableFor(c, me.userId(), Role.valueOf(me.role()));

        return new Result(c.getCaseNo(), c.getStatus(), c.getAccountKey(), c.getEntityId(),
                refs.get(c.getAssigneeId()), c.getRejectionReasonCode(), c.getCloseReason(), c.getCurrentDraftId(),
                c.getVersion(), c.getCreatedAt().truncatedTo(ChronoUnit.SECONDS),
                c.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS), history, actions);
    }

    private static String loginIdOf(UserRef ref) {
        return ref == null ? null : ref.loginId();
    }
}
