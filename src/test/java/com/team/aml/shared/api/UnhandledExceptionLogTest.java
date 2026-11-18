package com.team.aml.shared.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 500 처리기 로그 (SCRUM-272, 03_shared §8) — DB 오류(DataAccessException 계열)는 메시지·스택 대신
 * `unhandled exception error_type=… sql_state=… constraint=…` 한 줄(없는 칸은 뺌). 다른 예외는 지금처럼 스택.
 * Postgres 메시지의 `Detail: Key (…)=(값)` 에 계좌가 들어갈 수 있다. 스프링 컨텍스트 없이 핸들러만 부른다.
 */
@ExtendWith(OutputCaptureExtension.class)
class UnhandledExceptionLogTest {

    private static final String ACCOUNT = "10_8000EBD30";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_제약_위반은_클래스_SQLState_제약_이름만_한_줄이다(CapturedOutput output) {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "could not execute statement; Key (account_key)=(" + ACCOUNT + ")", duplicate());

        ResponseEntity<ErrorResponse> res = handler.unknown(e);

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(onlyLine(output)).contains("ERROR").endsWith(
                "unhandled exception error_type=DataIntegrityViolationException sql_state=23505 constraint=alerts_rule_account_window_uq");
        assertNoLeak(output);
    }

    @Test
    void 정상_JdbcTemplate_중복_키도_같은_모양이다(CapturedOutput output) {
        handler.unknown(new DuplicateKeyException("PreparedStatementCallback; " + ACCOUNT, duplicate()));

        assertThat(onlyLine(output)).endsWith(
                "unhandled exception error_type=DuplicateKeyException sql_state=23505 constraint=alerts_rule_account_window_uq");
        assertNoLeak(output);
    }

    @Test
    void 정상_응답_본문은_그대로_INTERNAL_ERROR와_고정_문구다() {
        ResponseEntity<ErrorResponse> res = handler.unknown(new DuplicateKeyException("dup " + ACCOUNT, duplicate()));

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody()).isEqualTo(ErrorResponse.of("INTERNAL_ERROR", "알 수 없는 오류"));
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_제약_이름이_없으면_constraint_칸을_뺀다(CapturedOutput output) {
        handler.unknown(new BadSqlGrammarException("select", "select * from ops.x where k = '" + ACCOUNT + "'",
                new SQLException("relation does not exist " + ACCOUNT, "42P01")));

        assertThat(onlyLine(output))
                .endsWith("unhandled exception error_type=BadSqlGrammarException sql_state=42P01")
                .doesNotContain("constraint=");
        assertNoLeak(output);
    }

    @Test
    void 경계_SQLException이_없는_DB_오류는_클래스만_남는다(CapturedOutput output) {
        handler.unknown(new DataRetrievalFailureException("no row for " + ACCOUNT));

        assertThat(onlyLine(output)).endsWith("unhandled exception error_type=DataRetrievalFailureException")
                .doesNotContain("sql_state=").doesNotContain("constraint=");
        assertNoLeak(output);
    }

    // --- 회귀 ---------------------------------------------------------------

    @Test
    void 회귀_DB_오류가_아닌_예외는_지금처럼_메시지와_스택이_남는다(CapturedOutput output) {
        handler.unknown(new IllegalStateException("probe-boom-zz9"));

        assertThat(output.getAll()).contains("unhandled exception").contains("probe-boom-zz9")
                .contains("at com.team.aml.shared.api.UnhandledExceptionLogTest");
    }

    // --- 도우미 -------------------------------------------------------------

    /** 실제 Postgres 23505 응답 모양(S·C·M·D·n 칸). Detail 에 계좌 키. */
    private static PSQLException duplicate() {
        return new PSQLException(new ServerErrorMessage("SERROR\0V ERROR\0C23505\0"
                + "Mduplicate key value violates unique constraint \"alerts_rule_account_window_uq\"\0"
                + "DKey (rule_ref_id, account_key, window_start)=(7, " + ACCOUNT + ", 2022-09-02 12:00:00) already exists.\0"
                + "sops\0talerts\0nalerts_rule_account_window_uq\0"));
    }

    private static String onlyLine(CapturedOutput output) {
        List<String> lines = output.getAll().lines().filter(l -> l.contains("unhandled exception")).toList();
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    private static void assertNoLeak(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("8000EBD30").doesNotContain("Detail").doesNotContain("Key (")
                .doesNotContain("\tat ");
    }
}
