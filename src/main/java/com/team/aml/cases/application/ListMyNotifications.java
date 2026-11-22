package com.team.aml.cases.application;

import com.team.aml.cases.domain.NotificationRow;
import com.team.aml.cases.domain.NotificationRows;
import com.team.aml.cases.domain.NotificationType;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 내 업무 알림 (SCRUM-355, 노션 「비동기 작업 알림 설계 문서」 6.5). case_history 를 읽기만 한다 — 알림 저장·전이 변경 없음.
 * 받는 사람은 지금 로그인한 사람뿐(인자로 남을 지정하지 않음). 열람 기록(access_logs)은 남기지 않는다 — 본문 없는 목록.
 *
 * <p>역할별(★A 11/22): 조사역 6종 · 승인자 승인 요청만 · 관리자(그 밖) 빈 목록 — 관리자의 배치 알림은 화면 작업 추적기 몫.
 * 역할은 세션 값이라 역할이 바뀌면 다시 로그인한 뒤부터 반영된다.
 *
 * <p>읽는 구간은 since &lt; created_at &le; server_time — 화면이 server_time 을 다음 since 로 주면 경계 초의 행이 두 번
 * 나오지 않는다. 목록과 total 은 REPEATABLE_READ 한 스냅샷에서 읽어 서로 어긋나지 않는다(ListPendingApprovals 와 같음,
 * 추가 검토 1).
 *
 * <p>server_time = 지금(초 버림) − {@link #COMMIT_LAG}. created_at 은 커밋 전에 찍혀서, 이 조회 뒤에 커밋된 행이
 * 이미 지난 구간에 들어가 사라지지 않게 한다 — 5초 안에 커밋된 행은 다음 조회에 나온다(추가 검토 2). 그보다 오래 걸린
 * 트랜잭션은 놓칠 수 있다(1단계 한계, 정확히 한 번은 알림 저장 2단계). 서버 시계가 뒤로 가도 server_time 은 받은 since
 * 보다 작아지지 않는다 — 작아지면 이미 받은 알림을 다시 읽는다(추가 검토 2).
 */
@Service
public class ListMyNotifications {

    /** since 가 없을 때 · 가장 오래 볼 수 있는 기간 · total 상한 (설계 6.5 — 화면은 99 를 "99+" 로). */
    static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    static final Duration MAX_WINDOW = Duration.ofDays(30);
    static final int TOTAL_CAP = 99;
    /** 상한을 지금보다 이만큼 앞에 둔다 — 조회 사이에 커밋되는 행을 놓치지 않을 여유. */
    static final Duration COMMIT_LAG = Duration.ofSeconds(5);

    public record Item(NotificationType type, String caseNo, String draftNo, String reasonCode, UserRef actor,
                       Instant at) {}

    /** total 은 since 뒤 전체 건수(size 와 무관, 99 에서 멈춤). serverTime 은 읽은 구간의 끝 — 화면이 다음 since 로 쓴다. */
    public record Result(List<Item> items, int total, Instant serverTime) {}

    private final NotificationRows rows;
    private final UserRefs userRefs;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public ListMyNotifications(NotificationRows rows, UserRefs userRefs, CurrentUser currentUser) {
        this(rows, userRefs, currentUser, Clock.systemUTC());
    }

    /** 테스트가 시계를 고정할 때. */
    ListMyNotifications(NotificationRows rows, UserRefs userRefs, CurrentUser currentUser, Clock clock) {
        this.rows = rows;
        this.userRefs = userRefs;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** since 가 null 이면 7일 전, 30일보다 오래되면 30일 전. size 범위(1~50)는 컨트롤러가 본다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result execute(Instant since, int size) {
        Instant now = clock.instant();
        Instant upper = now.truncatedTo(ChronoUnit.SECONDS).minus(COMMIT_LAG);
        Instant serverTime = since == null ? upper : max(upper, since);   // 응답 값이자 읽는 구간의 끝(포함)
        AppUserDetails me = currentUser.require();
        Optional<NotificationRows.Audience> audience = audienceOf(me.role());
        if (audience.isEmpty()) {
            return new Result(List.of(), 0, serverTime);
        }
        Instant from = since == null ? now.minus(DEFAULT_WINDOW) : max(since, now.minus(MAX_WINDOW));

        List<NotificationRow> found = rows.find(me.userId(), audience.get(), from, serverTime, size);
        Map<Long, UserRef> actors = userRefs.of(found.stream().map(NotificationRow::createdBy).toList());
        List<Item> items = found.stream().flatMap(r -> toItem(r, actors).stream()).toList();
        return new Result(items, rows.count(me.userId(), audience.get(), from, serverTime, TOTAL_CAP), serverTime);
    }

    private static Optional<NotificationRows.Audience> audienceOf(String role) {
        if (Role.INVESTIGATOR.name().equals(role)) {
            return Optional.of(NotificationRows.Audience.INVESTIGATOR);
        }
        if (Role.APPROVER.name().equals(role)) {
            return Optional.of(NotificationRows.Audience.APPROVER);
        }
        return Optional.empty();
    }

    /** 쿼리가 고른 동작만 오므로 empty 는 생기지 않지만, 생기면 그 줄을 뺀다(목록을 깨지 않음). */
    private static Optional<Item> toItem(NotificationRow r, Map<Long, UserRef> actors) {
        return NotificationType.of(r.action(), r.receivedByMe()).map(type -> new Item(type, r.caseNo(),
                type.carriesDraft() && r.currentDraftId() != null ? DisplayNumber.draftNo(r.currentDraftId()) : null,
                type == NotificationType.STR_REJECTED ? r.reasonCode() : null,
                actors.get(r.createdBy()), r.createdAt().truncatedTo(ChronoUnit.SECONDS)));
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
