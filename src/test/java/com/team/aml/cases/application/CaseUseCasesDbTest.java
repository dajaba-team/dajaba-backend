package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.cases.domain.CloseRequiresStrException;
import com.team.aml.cases.domain.InvalidAssigneeException;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.cases.domain.OpenedScore;
import com.team.aml.cases.domain.ScoreBand;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.user.domain.UserNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 화면에서 부르는 cases 유스케이스 — 배정·에스컬레이션·종료·목록·상세 (SCRUM-105). 실제 PostgreSQL. */
@Testcontainers
@SpringBootTest(properties = {   // 상세 score_band 계산용 (OpenCasesFromAlertsDbTest 와 같은 값)
        "aml.case-policy.version=test-v1",
        "aml.case-policy.low-to-medium=0.5",
        "aml.case-policy.medium-to-high=0.9"})
@ActiveProfiles("test")
class CaseUseCasesDbTest {

    private static final long SYSTEM = 1L;
    private static final AtomicLong SEQ = new AtomicLong();

    @Container
    @ServiceConnection(type = JdbcConnectionDetails.class)
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired AssignCase assign;
    @Autowired EscalateCase escalate;
    @Autowired CloseCase close;
    @Autowired ListCases list;
    @Autowired GetCaseDetail detail;
    @Autowired WasCaseAssignee wasAssignee;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private AppUserDetails inv;
    private AppUserDetails inv2;
    private AppUserDetails apr;
    private AppUserDetails admin;

    @BeforeEach
    void users() {
        long n = SEQ.incrementAndGet();   // 테스트마다 다른 사람 — 목록 담당자 필터가 서로 섞이지 않게
        inv = user("c_inv" + n, "INVESTIGATOR");
        inv2 = user("c_invb" + n, "INVESTIGATOR");
        apr = user("c_apr" + n, "APPROVER");
        admin = user("c_adm" + n, "ADMIN");
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 배정 ──────────────────────────────────────────────────

    @Test
    void 정상_ADMIN이_조사역을_지정하면_REVIEWING_ASSIGN_이력_version_증가() {
        Case c = newCase();
        login(admin);

        CaseChangeResult r = assign.execute(c.getCaseNo(), c.getVersion(), inv2.loginId());

        assertThat(r.status()).isEqualTo(CaseStatus.REVIEWING);
        assertThat(r.assignee()).isEqualTo(new UserRef(inv2.loginId(), inv2.displayName()));
        assertThat(r.version()).isEqualTo(c.getVersion() + 1);
        assertThat(r.updatedAt()).isNotNull();
        assertThat(cases.findById(c.getId()).orElseThrow().getAssigneeId()).isEqualTo(inv2.userId());
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
            assertThat(h.getAction()).isEqualTo(CaseHistoryAction.ASSIGN);
            assertThat(h.getCreatedBy()).isEqualTo(admin.userId());
        });
    }

    @Test
    void 실패_조사역_승인자가_직접_부르면_케이스를_읽기_전에_거절하고_아무것도_안_바뀐다() {   // SCRUM-339 — URL 을 안 거치는 호출
        Case c = newCase();
        login(inv);
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), null))
                .isInstanceOf(AccessDeniedException.class);       // 비움 = 예전 "본인"
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), inv.loginId()))
                .isInstanceOf(AccessDeniedException.class);       // 본인 지정
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), inv2.loginId()))
                .isInstanceOf(AccessDeniedException.class);       // 남 지정
        assertThatThrownBy(() -> assign.execute("CASE-2099-999999", 0, null))
                .isInstanceOf(AccessDeniedException.class);       // 없는 케이스도 404 가 아니라 거절 먼저
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion() + 1, null))
                .isInstanceOf(AccessDeniedException.class);       // 버전 검사보다 먼저
        login(apr);
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), inv.loginId()))
                .isInstanceOf(AccessDeniedException.class);

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(after.getAssigneeId()).isNull();
        assertThat(after.getVersion()).isEqualTo(c.getVersion());
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).hasSize(1);   // OPEN 만
    }

    @Test
    void 실패_배정_대상이_잘못되면_아무것도_안_바뀐다() {
        Case c = newCase();
        login(admin);
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), apr.loginId()))
                .isInstanceOf(InvalidAssigneeException.class);       // 조사역 아님
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), null))
                .isInstanceOf(InvalidAssigneeException.class)        // 대상 비움 — ADMIN 본인은 조사역 아님
                .satisfies(e -> assertThat(((InvalidAssigneeException) e).details())
                        .containsEntry("reason", "NOT_ACTIVE_INVESTIGATOR"));
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), "nobody"))
                .isInstanceOf(UserNotFoundException.class);
        jdbc.update("update ops.users set is_active = false where id = ?", inv.userId());
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), inv.loginId()))
                .isInstanceOf(InvalidAssigneeException.class);       // 비활성

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(after.getVersion()).isEqualTo(c.getVersion());
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).hasSize(1);   // OPEN 만
    }

    @Test
    void 실패_화면이_읽은_뒤_바뀌었으면_409_이미_배정됐으면_상태_오류() {
        Case c = newCase();
        login(admin);
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion() + 1, inv.loginId()))
                .isInstanceOf(OptimisticLockException.class);
        CaseChangeResult first = assign.execute(c.getCaseNo(), c.getVersion(), inv.loginId());

        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), c.getVersion(), inv2.loginId()))   // 옛 version
                .isInstanceOf(OptimisticLockException.class);
        assertThatThrownBy(() -> assign.execute(c.getCaseNo(), first.version(), inv2.loginId()))  // 최신 version, 이미 REVIEWING
                .isInstanceOf(InvalidCaseTransitionException.class);
    }

    @Test
    void 실패_없는_케이스는_404() {
        login(admin);
        assertThatThrownBy(() -> assign.execute("CASE-2099-999999", 0, inv.loginId()))
                .isInstanceOf(CaseNotFoundException.class);
    }

    // ── 에스컬레이션·종료 ─────────────────────────────────────

    @Test
    void 정상_담당이_에스컬레이션하면_ESCALATED_사유가_이력에() {
        Case c = reviewing();
        login(inv);

        CaseChangeResult r = escalate.execute(c.getCaseNo(), c.getVersion(), "STR 초안 작성 필요");

        assertThat(r.status()).isEqualTo(CaseStatus.ESCALATED);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
            assertThat(h.getAction()).isEqualTo(CaseHistoryAction.ESCALATE);
            assertThat(h.getComment()).isEqualTo("STR 초안 작성 필요");
        });
    }

    @Test
    void 실패_담당이_아니면_403_NOT_ASSIGNEE_상태는_그대로() {
        Case c = reviewing();
        login(inv2);
        assertThatThrownBy(() -> escalate.execute(c.getCaseNo(), c.getVersion(), "사유"))
                .isInstanceOf(NotAssigneeException.class);
        assertThatThrownBy(() -> close.execute(c.getCaseNo(), c.getVersion(), CloseReason.NO_ACTION, "사유"))
                .isInstanceOf(NotAssigneeException.class);
        assertThat(cases.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.REVIEWING);
    }

    @Test
    void 정상_초안_없는_종결은_CLOSED와_종결_사유() {
        Case c = reviewing();
        login(inv);

        CaseChangeResult r = close.execute(c.getCaseNo(), c.getVersion(), CloseReason.FALSE_POSITIVE, "오탐으로 판단");

        assertThat(r.status()).isEqualTo(CaseStatus.CLOSED);
        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getCloseReason()).isEqualTo(CloseReason.FALSE_POSITIVE);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
            assertThat(h.getAction()).isEqualTo(CaseHistoryAction.CLOSE);
            assertThat(h.getReasonCode()).isEqualTo("FALSE_POSITIVE");
        });
    }

    @Test
    void 실패_초안이_있으면_409_CLOSE_REQUIRES_STR() {
        Case c = reviewing();
        long caseId = c.getId();
        tx().executeWithoutResult(s -> {
            Case e = cases.findById(caseId).orElseThrow();
            e.escalate(inv.userId(), "STR");
            histories.saveAll(cases.save(e).takePendingHistories());
        });
        long draftId = newDraft(caseId);
        Case drafted = tx().execute(s -> {
            Case d = cases.findById(caseId).orElseThrow();
            d.attachDraft(draftId, inv.userId());
            Case saved = cases.saveAndFlush(d);
            histories.saveAll(saved.takePendingHistories());
            return saved;
        });
        login(inv);

        assertThatThrownBy(() -> close.execute(drafted.getCaseNo(), drafted.getVersion(), CloseReason.NO_ACTION, "종료"))
                .isInstanceOf(CloseRequiresStrException.class);
        assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(CaseStatus.DRAFTED);
    }

    // ── 목록 ──────────────────────────────────────────────────

    @Test
    void 목록은_상태_담당자_기간으로_거르고_페이지를_나눈다() {
        Case a = reviewing();   // inv 담당
        Case b = reviewing();   // inv 담당
        Case other = newCase(); // OPEN, 담당 없음
        login(inv);

        Page<ListCases.Item> mine = list.execute(new ListCases.Query(null, "me", null, null, "created_at,asc", 0, 20));
        assertThat(mine.getContent()).extracting(ListCases.Item::caseNo).containsExactly(a.getCaseNo(), b.getCaseNo());
        assertThat(mine.getContent().get(0).assignee()).isEqualTo(new UserRef(inv.loginId(), inv.displayName()));
        assertThat(mine.getContent().get(0).accountKey()).isEqualTo(a.getAccountKey());

        Page<ListCases.Item> byLogin = list.execute(new ListCases.Query(Set.of(CaseStatus.REVIEWING), inv.loginId(),
                null, null, "score,desc", 0, 1));
        assertThat(byLogin.getTotalElements()).isEqualTo(2);
        assertThat(byLogin.getContent()).extracting(ListCases.Item::caseNo).containsExactly(b.getCaseNo()); // 최신 먼저

        assertThat(list.execute(new ListCases.Query(Set.of(CaseStatus.OPEN), null, null, null, null, 0, 100))
                .getContent()).extracting(ListCases.Item::caseNo).contains(other.getCaseNo())
                .doesNotContain(a.getCaseNo(), b.getCaseNo());
        assertThat(list.execute(new ListCases.Query(null, "nobody", null, null, null, 0, 20)).getTotalElements()).isZero();

        LocalDate today = LocalDate.now(ListCases.BUSINESS_ZONE);
        assertThat(list.execute(new ListCases.Query(null, "me", today, today, null, 0, 20)).getTotalElements())
                .isEqualTo(2);
        assertThat(list.execute(new ListCases.Query(null, "me", today.plusDays(1), null, null, 0, 20))
                .getTotalElements()).isZero();
    }

    // ── 상세 ──────────────────────────────────────────────────

    @Test
    void 상세는_열람_기록을_남기고_최근_이력과_버튼을_준다() {
        Case c = reviewing();
        login(inv);

        GetCaseDetail.Result r = detail.execute(c.getCaseNo());

        assertThat(r.status()).isEqualTo(CaseStatus.REVIEWING);
        assertThat(r.assignee().loginId()).isEqualTo(inv.loginId());
        assertThat(r.history()).extracting(GetCaseDetail.History::action)
                .containsExactly(CaseHistoryAction.ASSIGN, CaseHistoryAction.OPEN);   // 최신 먼저
        assertThat(r.history().get(0).createdBy()).isEqualTo(admin.loginId());   // 관리자 배정
        assertThat(r.availableActions()).containsExactly(CaseAction.UPDATE_NOTES, CaseAction.REFRESH_SUMMARY,
                CaseAction.ESCALATE, CaseAction.CLOSE);
        assertThat(viewLogs(c.getId(), inv.userId())).isEqualTo(1);
        assertThat(r.alert().alertNo()).startsWith("ALT-20230102-");
        assertThat(r.alert().ruleId()).startsWith("R_UC_");
        assertThat(r.alert().transactionCount()).isZero();
        assertThat(r.latestScore().scoreStatus()).isEqualTo("PENDING");   // 점수 전 — 칸은 null, reasons 는 []
        assertThat(r.latestScore().score()).isNull();
        assertThat(r.latestScore().scoreBand()).isNull();
        assertThat(r.policyVersion()).isNull();
        assertThat(r.latestScore().reasons().isArray()).isTrue();
        assertThat(r.latestScore().reasons()).isEmpty();

        login(apr);
        assertThat(detail.execute(c.getCaseNo()).availableActions()).isEmpty();
        assertThat(viewLogs(c.getId(), apr.userId())).isEqualTo(1);
    }

    @Test
    void 상세는_경보의_점수와_reasons_를_그대로_준다() {
        Case c = newCase();
        jdbc.update("update ops.alerts set score_status = 'SCORED' where id = ?", c.getAlertId());
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, 0.91, 0.7, 'xgb-test', 'f1', ?, now(),
                        '{"data_quality_status": "OK", "reasons": [{"feature": "fan_in_24h"}]}'::jsonb)""",
                c.getAlertId(), "p-" + c.getAlertId());
        login(inv);

        GetCaseDetail.Result r = detail.execute(c.getCaseNo());
        GetCaseDetail.LatestScore s = r.latestScore();
        assertThat(r.policyVersion()).isEqualTo("test-v1");     // 맨 위 score·score_band·policy_version 은 Q2 MVP 로 같은 값

        assertThat(s.scoreStatus()).isEqualTo("SCORED");
        assertThat(s.score()).isEqualByComparingTo("0.91");
        assertThat(s.scoreBand()).isEqualTo(ScoreBand.HIGH);   // 0.91 ≥ 0.9
        assertThat(s.modelVersion()).isEqualTo("xgb-test");
        assertThat(s.featureVersion()).isEqualTo("f1");
        assertThat(s.dataQualityStatus()).isEqualTo("OK");
        assertThat(s.reasons().get(0).get("feature").asText()).isEqualTo("fan_in_24h");
    }

    @Test
    void 실패_없는_케이스_상세는_404이고_열람_기록이_없다() {
        login(inv);
        long before = jdbc.queryForObject("select count(*) from ops.access_logs where user_id = ?", Long.class, inv.userId());
        assertThatThrownBy(() -> detail.execute("CASE-2099-999998")).isInstanceOf(CaseNotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from ops.access_logs where user_id = ?", Long.class, inv.userId()))
                .isEqualTo(before);
    }

    // ── 예전 담당 (V010, 미결 32) ─────────────────────────────────

    @Test
    void 정상_재배정하면_넘긴_사람과_받은_사람이_이력에_남고_둘_다_담당했던_사람이다() {
        Case c = reviewing();
        tx().executeWithoutResult(s -> {
            Case found = cases.findById(c.getId()).orElseThrow();
            found.reassign(inv2.userId(), admin.userId(), "업무 분담");
            histories.saveAll(cases.saveAndFlush(found).takePendingHistories());
        });

        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId()))
                .extracting(h -> h.getAction(), h -> h.getFromAssigneeId(), h -> h.getToAssigneeId())
                .containsExactly(
                        tuple(CaseHistoryAction.OPEN, null, null),
                        tuple(CaseHistoryAction.ASSIGN, null, inv.userId()),
                        tuple(CaseHistoryAction.REASSIGN, inv.userId(), inv2.userId()));
        assertThat(wasAssignee.execute(c.getId(), inv.userId())).isTrue();    // 예전 담당 — 이력으로
        assertThat(wasAssignee.execute(c.getId(), inv2.userId())).isTrue();   // 지금 담당
        assertThat(wasAssignee.execute(c.getId(), apr.userId())).isFalse();
    }

    @Test
    void 경계_V010_전_이력이라_받은_사람_칸이_비어도_지금_담당은_담당자로_본다() {
        Case c = newCase();
        // V010 전에 배정된 케이스처럼: 케이스에는 담당자가 있는데 ASSIGN 이력(받은 사람)이 없다
        jdbc.update("update ops.cases set status = 'REVIEWING', assignee_id = ? where id = ?", inv.userId(), c.getId());

        assertThat(wasAssignee.execute(c.getId(), inv.userId())).isTrue();
        assertThat(wasAssignee.execute(c.getId(), inv2.userId())).isFalse();
    }

    @Test
    void 경계_V010_전에_배정받고_그_뒤_넘긴_사람은_넘긴_사람_칸으로_예전_담당이다() {
        Case c = newCase();
        // V010 전에 배정된 케이스처럼 ASSIGN 이력(받은 사람)이 없고, 그 뒤 재배정해 넘긴 사람 칸에만 남는다 (SCRUM-355 리뷰)
        jdbc.update("update ops.cases set status = 'REVIEWING', assignee_id = ? where id = ?", inv.userId(), c.getId());
        tx().executeWithoutResult(s -> {
            Case found = cases.findById(c.getId()).orElseThrow();
            found.reassign(inv2.userId(), admin.userId(), "업무 분담");
            histories.saveAll(cases.saveAndFlush(found).takePendingHistories());
        });

        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId()))
                .noneMatch(h -> inv.userId().equals(h.getToAssigneeId()));
        assertThat(wasAssignee.execute(c.getId(), inv.userId())).isTrue();    // 넘긴 사람 이력으로
        assertThat(wasAssignee.execute(c.getId(), apr.userId())).isFalse();
    }

    @Test
    void 실패_없는_케이스면_예전_담당_조회도_404() {
        assertThatThrownBy(() -> wasAssignee.execute(-1L, inv.userId())).isInstanceOf(CaseNotFoundException.class);
    }

    // ── 목록 점수 정렬 (SCRUM-236, V013 생성 당시 점수) ───────────────

    @Test
    void 정상_점수_순_정렬은_없는_점수를_맨_뒤에_두고_같은_점수는_최신_생성_먼저() {
        AppUserDetails sorter = user("sort_inv_" + SEQ.incrementAndGet(), "INVESTIGATOR");
        Case a = scored("0.95", sorter);
        Case b = scored("0.91", sorter);
        Case none = scored(null, sorter);   // V013 전처럼 점수 스냅샷 없음
        Case d = scored("0.91", sorter);    // b 와 같은 점수, 더 늦게 생성
        login(sorter);

        assertThat(sortedBy("score,desc", 0, 20)).containsExactly(
                a.getCaseNo(), d.getCaseNo(), b.getCaseNo(), none.getCaseNo());
        assertThat(sortedBy("score,asc", 0, 20)).containsExactly(     // 오름차순도 없는 점수는 맨 뒤 (0 으로 안 바꿈)
                d.getCaseNo(), b.getCaseNo(), a.getCaseNo(), none.getCaseNo());
        assertThat(sortedBy("score,desc", 1, 2)).containsExactly(     // 전체를 정렬한 뒤 페이지를 자른다
                b.getCaseNo(), none.getCaseNo());
        assertThat(sortedBy(null, 0, 1)).containsExactly(a.getCaseNo()); // 기본은 score,desc
    }

    @Test
    void 정상_목록_한_줄의_점수는_생성_당시_점수_등급과_정책은_지금_정책() {
        AppUserDetails viewer = user("score_inv_" + SEQ.incrementAndGet(), "INVESTIGATOR");
        Case high = scored("0.95", viewer);
        Case none = scored(null, viewer);
        login(viewer);

        var items = list.execute(new ListCases.Query(null, "me", null, null, "score,desc", 0, 20)).getContent();
        assertThat(items.get(0)).satisfies(i -> {
            assertThat(i.caseNo()).isEqualTo(high.getCaseNo());
            assertThat(i.score()).isEqualByComparingTo("0.95");
            assertThat(i.scoreBand()).isEqualTo(ScoreBand.HIGH);      // 정책 test-v1: ≥ 0.9
            assertThat(i.policyVersion()).isEqualTo("test-v1");
        });
        assertThat(items.get(1)).satisfies(i -> {
            assertThat(i.caseNo()).isEqualTo(none.getCaseNo());
            assertThat(i.score()).isNull();
            assertThat(i.scoreBand()).isNull();
            assertThat(i.policyVersion()).isNull();
        });
        // 상세는 생성 당시 스냅샷을 latest_score 와 따로 준다 (SCRUM-237)
        assertThat(detail.execute(high.getCaseNo()).openedScore()).satisfies(o -> {
            assertThat(o.score()).isEqualByComparingTo("0.95");
            assertThat(o.modelVersion()).isEqualTo("xgb-test");
            assertThat(o.policyVersion()).isEqualTo("test-v1");
            assertThat(o.mediumToHigh()).isEqualByComparingTo("0.9");
        });
        assertThat(detail.execute(none.getCaseNo()).openedScore()).isNull();
    }

    private java.util.List<String> sortedBy(String sort, int page, int size) {
        return list.execute(new ListCases.Query(null, "me", null, null, sort, page, size)).getContent().stream()
                .map(ListCases.Item::caseNo).toList();
    }

    /** 생성 당시 점수가 있는(또는 없는) 케이스를 who 담당으로. score 가 null 이면 스냅샷 없음. */
    private Case scored(String score, AppUserDetails who) {
        long alertId = newAlert();
        OpenedScore opened = score == null ? null : new OpenedScore(alertId, new BigDecimal(score), "xgb-test", "f1",
                "test-v1", new BigDecimal("0.5"), new BigDecimal("0.9"));
        return tx().execute(s -> {
            Case c = Case.open("CASE-2023-%06d".formatted(SEQ.incrementAndGet()), alertId, "010_U" + alertId, null,
                    opened, SYSTEM);
            c.assign(who.userId(), admin.userId());   // 관리자 배정 (SCRUM-339)
            Case saved = cases.saveAndFlush(c);
            histories.saveAll(saved.takePendingHistories());
            return saved;
        });
    }

    // ── 준비 ──────────────────────────────────────────────────

    private Case newCase() {
        long alertId = newAlert();
        return tx().execute(s -> {
            Case c = Case.open("CASE-2023-%06d".formatted(SEQ.incrementAndGet()), alertId, "010_U" + alertId, null, SYSTEM);
            Case saved = cases.saveAndFlush(c);
            histories.saveAll(saved.takePendingHistories());
            return saved;
        });
    }

    /** OPEN → admin 이 inv 에게 배정한 REVIEWING (SCRUM-339). */
    @Test
    void 예전_조사역_자기_배정_이력은_그대로_보이고_담당_조사역은_계속_조사한다() {   // SCRUM-339 — 이력 안 바꿈
        Case c = reviewing(inv);
        login(inv);

        GetCaseDetail.Result r = detail.execute(c.getCaseNo());

        assertThat(r.history().get(0).action()).isEqualTo(CaseHistoryAction.ASSIGN);
        assertThat(r.history().get(0).createdBy()).isEqualTo(inv.loginId());
        assertThat(r.availableActions()).contains(CaseAction.ESCALATE, CaseAction.CLOSE).doesNotContain(CaseAction.ASSIGN);
    }

    /** OPEN → admin 이 inv 에게 배정한 REVIEWING (SCRUM-339). */
    private Case reviewing() {
        return reviewing(admin);
    }

    /** actor 가 배정 이력 작성자 — 예전 조사역 자기 배정 이력을 만들 때만 inv. */
    private Case reviewing(AppUserDetails actor) {
        Case opened = newCase();
        return tx().execute(s -> {
            Case c = cases.findById(opened.getId()).orElseThrow();
            c.assign(inv.userId(), actor.userId());
            Case saved = cases.saveAndFlush(c);
            histories.saveAll(saved.takePendingHistories());
            return saved;
        });
    }

    private long viewLogs(long caseId, long userId) {
        return jdbc.queryForObject("""
                select count(*) from ops.access_logs
                where action = 'VIEW' and target_type = 'CASE' and target_id = ? and user_id = ?
                """, Long.class, caseId, userId);
    }

    private AppUserDetails user(String loginId, String role) {
        long id = jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, ?, 1, 1) returning id
                """, Long.class, loginId, "이름 " + loginId, role);
        return new AppUserDetails(id, loginId, "이름 " + loginId, role);
    }

    private void login(AppUserDetails u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private long newAlert() {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-01-02', 'RUNNING', 1) returning id
                """, Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id
                """, Long.class, "R_UC_" + n);
        return jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-01-02 00:00:00', '2023-01-02 23:59:59', '2023-01-02 23:59:59',
                        '{}'::jsonb, '2023-01-02')
                returning id
                """, Long.class, "ALT-20230102-%06d".formatted(n), batchId, ruleId, "010_U" + n);
    }

    private long newDraft(long caseId) {
        long draftId = jdbc.queryForObject("select nextval('ops.str_draft_id_seq')", Long.class);
        jdbc.update("""
                insert into ops.str_draft_requests (draft_id, case_id, request_snapshot, created_by, updated_by)
                values (?, ?, '{}'::jsonb, 1, 1)
                """, draftId, caseId);
        jdbc.update("update ops.str_draft_requests set status = 'SUCCEEDED', finished_at = now() where draft_id = ?", draftId);
        jdbc.update("""
                insert into ops.str_drafts (id, case_id, generation_id, generator_version, produced_at, payload,
                                            created_by, updated_by)
                values (?, ?, ?, 'test', '2026-11-03T01:10:00Z', '{"facts":{}}'::jsonb, 1, 1)
                """, draftId, caseId, "g-" + draftId);
        return draftId;
    }
}
