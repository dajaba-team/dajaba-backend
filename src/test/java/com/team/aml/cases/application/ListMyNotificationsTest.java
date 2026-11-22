package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.NotificationRow;
import com.team.aml.cases.domain.NotificationRows;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 고정 시계로 읽는 구간을 본다 (SCRUM-355, 추가 검토 2). DB 없이 — 저장소는 받은 구간으로 걸러 주는 가짜.
 * 구간: since(없으면 7일 전, 30일보다 오래되면 30일 전) &lt; created_at &le; server_time(= 지금 초 버림 − 5초, since 보다 작아지지 않음).
 */
class ListMyNotificationsTest {

    private static final Instant BASE = Instant.parse("2026-11-24T02:10:00Z");

    private final FakeRows rows = new FakeRows();
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final UserRefs userRefs = mock(UserRefs.class);
    private final MovableClock clock = new MovableClock();
    private ListMyNotifications useCase;

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(new AppUserDetails(7L, "inv01", "조사역 1", "INVESTIGATOR"));
        useCase = new ListMyNotifications(rows, userRefs, currentUser, clock);
    }

    @Test
    void 정상_since_없으면_7일_전부터_지금_초_버림_빼기_5초까지() {
        clock.now = BASE.plusMillis(800);
        ListMyNotifications.Result r = useCase.execute(null, 20);

        assertThat(rows.lastSince).isEqualTo(BASE.plusMillis(800).minus(Duration.ofDays(7)));
        assertThat(rows.lastUntil).isEqualTo(BASE.minusSeconds(5));
        assertThat(r.serverTime()).isEqualTo(BASE.minusSeconds(5));
    }

    @Test
    void 경계_30일보다_오래된_since_는_30일_전으로_그_안은_그대로() {
        clock.now = BASE;
        useCase.execute(BASE.minus(Duration.ofDays(40)), 20);
        assertThat(rows.lastSince).isEqualTo(BASE.minus(Duration.ofDays(30)));

        useCase.execute(BASE.minus(Duration.ofDays(10)), 20);
        assertThat(rows.lastSince).isEqualTo(BASE.minus(Duration.ofDays(10)));
    }

    @Test
    void 실패_시계가_뒤로_가도_server_time_은_since_보다_작아지지_않아_같은_알림이_한_번만() {
        rows.events.add(row(BASE.plusSeconds(9)));
        // server_time 이 10초 → (시계가 뒤로) 8초 → 11초가 되는 세 번 — 화면은 받은 server_time 을 다음 since 로 준다
        int seen = 0;
        Instant since = null;
        for (int serverSecond : new int[]{10, 8, 11}) {
            clock.now = BASE.plusSeconds(serverSecond).plus(ListMyNotifications.COMMIT_LAG);
            ListMyNotifications.Result r = useCase.execute(since, 20);
            seen += r.items().size();
            if (since != null) {
                assertThat(r.serverTime()).isAfterOrEqualTo(since);
            }
            since = r.serverTime();
        }
        assertThat(seen).isEqualTo(1);
    }

    @Test
    void 경계_since_가_미래면_빈_목록이고_server_time_은_since_그대로() {
        clock.now = BASE;
        Instant future = BASE.plus(Duration.ofDays(1));
        ListMyNotifications.Result r = useCase.execute(future, 20);
        assertThat(r.items()).isEmpty();
        assertThat(r.serverTime()).isEqualTo(future);
    }

    private static NotificationRow row(Instant at) {
        return new NotificationRow(CaseHistoryAction.ASSIGN, true, "CASE-2023-000001", null, null, 3L, at);
    }

    /** 받은 구간(since &lt; at &le; until)으로 거르는 저장소. 마지막 구간을 기억한다. */
    private static final class FakeRows implements NotificationRows {
        final List<NotificationRow> events = new ArrayList<>();
        Instant lastSince;
        Instant lastUntil;

        @Override
        public List<NotificationRow> find(long me, Audience audience, Instant since, Instant until, int limit) {
            lastSince = since;
            lastUntil = until;
            return events.stream().filter(e -> e.createdAt().isAfter(since) && !e.createdAt().isAfter(until))
                    .limit(limit).toList();
        }

        @Override
        public int count(long me, Audience audience, Instant since, Instant until, int cap) {
            return (int) Math.min(cap, find(me, audience, since, until, Integer.MAX_VALUE).size());
        }
    }

    private static final class MovableClock extends Clock {
        Instant now = BASE;

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
