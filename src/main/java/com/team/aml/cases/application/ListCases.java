package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 케이스 목록 (CASES 명세 API-01, 계약서 C1). 조회 3역할 모두 전체 케이스를 본다.
 * 형식 검사(size 최대 100, sort 화이트리스트, from ≤ to)는 컨트롤러 @Valid 몫(SCRUM-106) — 여기는 검사된 값을 받는다.
 *
 * <p><b>점수(score·score_band·policy_version)는 아직 null.</b> detection 에 경보 점수 공개 조회가 없어서다(SCRUM-152).
 * 그래서 sort=score 는 "점수 전부 null" 과 같은 결과 — 명세의 동률 규칙대로 created_at DESC, id DESC.
 */
@Service
public class ListCases {

    /** 케이스 생성일(from·to) 은 한국 날짜로 자른다 (CASES 명세 API-01 "한국 날짜 검색 설계안"). */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    /** 허용 정렬 필드 (계약서 C1). 컨트롤러가 이 목록으로 검사한다. */
    public static final Set<String> SORT_FIELDS = Set.of("score", "created_at", "status");

    /** 기간을 안 주면 쓰는 경계 — 저장소는 null 기간을 받지 않는다. */
    private static final Instant NO_LOWER_BOUND = Instant.EPOCH;
    private static final Instant NO_UPPER_BOUND = Instant.parse("9999-12-31T00:00:00Z");

    /**
     * @param statuses 비어 있으면 전체
     * @param assignee null 이면 전체, "me" 면 로그인 사용자, 그 밖은 login_id
     * @param from     케이스 생성일 시작(포함), null 이면 제한 없음
     * @param to       케이스 생성일 끝(포함), null 이면 제한 없음
     * @param sort     "필드,asc|desc" — 필드는 SORT_FIELDS
     */
    public record Query(Set<CaseStatus> statuses, String assignee, LocalDate from, LocalDate to,
                        String sort, int page, int size) {
        public Query {
            statuses = statuses == null || statuses.isEmpty() ? EnumSet.allOf(CaseStatus.class) : Set.copyOf(statuses);
            sort = sort == null ? "score,desc" : sort;
        }
    }

    /**
     * 목록 한 줄. accountKey 는 내부 키 — 가린 account_no·account_ref 로 바꾸는 건 컨트롤러 응답 몫(SCRUM-106).
     * availableActions 는 상세와 같은 CaseAction.availableFor — 목록의 [배정]·[담당자 변경]도 이것만 보고 켠다(#23 리뷰).
     */
    public record Item(String caseNo, CaseStatus status, String accountKey, String entityId, UserRef assignee,
                       RejectionReasonCode rejectionReasonCode, long version, Instant createdAt,
                       List<CaseAction> availableActions) {
        public Item {
            availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
        }
    }

    private final CaseRepository cases;
    private final UserRepository users;
    private final UserRefs userRefs;
    private final CurrentUser currentUser;

    public ListCases(CaseRepository cases, UserRepository users, UserRefs userRefs, CurrentUser currentUser) {
        this.cases = cases;
        this.users = users;
        this.userRefs = userRefs;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<Item> execute(Query q) {
        PageRequest pageable = PageRequest.of(q.page(), q.size(), sortOf(q.sort()));
        Optional<Long> assigneeId = resolveAssignee(q.assignee());
        if (q.assignee() != null && assigneeId.isEmpty()) {
            return Page.empty(pageable);   // 없는 login_id — 그 사람이 담당한 케이스는 없다
        }
        Instant from = q.from() == null ? NO_LOWER_BOUND : q.from().atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = q.to() == null ? NO_UPPER_BOUND
                : q.to().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        Page<Case> found = cases.search(q.statuses(), assigneeId.orElse(null), from, toExclusive, pageable);
        Map<Long, UserRef> refs = userRefs.of(found.map(Case::getAssigneeId).getContent());
        AppUserDetails me = currentUser.require();
        Role role = Role.valueOf(me.role());
        return found.map(c -> new Item(c.getCaseNo(), c.getStatus(), c.getAccountKey(), c.getEntityId(),
                refs.get(c.getAssigneeId()), c.getRejectionReasonCode(), c.getVersion(),
                c.getCreatedAt().truncatedTo(ChronoUnit.SECONDS), CaseAction.availableFor(c, me.userId(), role)));
    }

    private Optional<Long> resolveAssignee(String assignee) {
        if (assignee == null) {
            return Optional.empty();
        }
        if ("me".equals(assignee)) {
            return Optional.of(currentUser.require().userId());
        }
        return users.findByLoginId(assignee).map(User::getId);
    }

    /** 동률은 created_at DESC, id DESC 로 고정해 페이지 사이 중복·누락을 막는다 (명세 API-01). */
    static Sort sortOf(String sort) {
        String[] parts = sort.split(",", 2);
        if (parts.length != 2 || !SORT_FIELDS.contains(parts[0]) || !Set.of("asc", "desc").contains(parts[1])) {
            throw new IllegalArgumentException("허용되지 않은 정렬: " + sort);
        }
        Sort.Direction direction = Sort.Direction.fromString(parts[1]);
        Sort tieBreak = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return switch (parts[0]) {
            // TODO(SCRUM-152, 미결 23): detection 점수 공개 조회가 생기면 점수 순(null 은 맨 뒤) + 동률 규칙으로
            case "score" -> tieBreak;
            case "created_at" -> Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
            default -> Sort.by(direction, "status").and(tieBreak);
        };
    }
}
