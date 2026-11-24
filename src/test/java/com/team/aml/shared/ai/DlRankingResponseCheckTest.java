package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.function.Consumer;

import static com.team.aml.shared.ai.DlRankingExamples.alert;
import static com.team.aml.shared.ai.DlRankingExamples.at;
import static com.team.aml.shared.ai.DlRankingExamples.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-376 — ML+DL 조사 순위 응답 확인 `requireDlRankingResponse` (AI `dl_ranking.py` 규칙 + 보낸 날짜 대조).
 * {@link DlRankingExamples#valid} 를 기준으로 칸 하나씩 바꿔 본다. 위반은 모두 502 `AI_BAD_RESPONSE`, 메시지엔 칸 이름만
 * (경보 번호·점수 없음).
 */
class DlRankingResponseCheckTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 7);
    private static final ObjectMapper OM = new ObjectMapper();

    private static void check(JsonNode b) {
        WebClientAiClient.requireDlRankingResponse(DATE, b);
    }

    private static void assertBad(JsonNode b, String field) {
        Throwable e = catchThrowable(() -> check(b));
        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).contains(field).doesNotContain("ALT-").doesNotContain("0.9");
        });
    }

    private static ObjectNode with(Consumer<ObjectNode> change) {
        ObjectNode b = valid(DATE);
        change.accept(b);
        return b;
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_세_이유가_섞이고_ML_UNSCORED_에_dl_score_가_있는_응답이_통과한다() {
        ObjectNode b = valid(DATE);
        assertThat(at(b, 4).get("fallback_reason").textValue()).isEqualTo("ML_UNSCORED");
        assertThat(at(b, 4).get("dl_score").doubleValue()).isEqualTo(0.5);   // AI 는 ML 없는 경보에도 DL 점수를 채움
        assertThatCode(() -> check(b)).doesNotThrowAnyException();
    }

    @Test
    void 정상_모르는_칸은_받아_둔다() {
        assertThatCode(() -> check(with(b -> {
            b.put("extra", 1);
            at(b, 0).put("extra", "x");
        }))).doesNotThrowAnyException();
    }

    @Test
    void 경계_점수_0과_1_은_통과한다() {
        assertThatCode(() -> check(with(b -> {
            at(b, 0).put("ml_score", 1.0);
            at(b, 0).put("dl_score", 0.0);
            at(b, 5).put("ml_failure_code", "X");
        }))).doesNotThrowAnyException();
    }

    @Test
    void 경계_ML_점수_있는_경보가_없어도_통과한다() {
        ObjectNode b = valid(DATE);
        ArrayNode alerts = b.putArray("alerts");
        alerts.add(alert(DATE, 4, "R01", null, "TRANSACTION_NOT_FOUND", 0.5, 2, 2, "ML_UNSCORED", null, null));
        b.put("linked_alerts", 0);
        b.put("ml_only_alerts", 0);
        b.put("unscored_alerts", 1);
        assertThatCode(() -> check(b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_alert_id_숫자_18자리까지_통과하고_19자리는_실패() {
        assertThatCode(() -> check(with(b -> at(b, 5).put("alert_id", "ALT-20220907-" + "9".repeat(18)))))
                .doesNotThrowAnyException();
        assertBad(with(b -> at(b, 5).put("alert_id", "ALT-20220907-" + "9".repeat(19))), "alert_id");
    }

    // --- 맨 위 칸 ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"scan_date", "snapshot_sha256", "ml_model", "dl_model", "evidence_transactions",
            "scored_transactions", "linked_alerts", "ml_only_alerts", "unscored_alerts", "alerts"})
    void 실패_필수_칸이_없거나_null_이면(String field) {
        assertBad(with(b -> b.remove(field)), field);
        assertBad(with(b -> b.putNull(field)), field);
    }

    @Test
    void 실패_본문이_객체가_아니면() {
        assertBad(OM.createArrayNode(), "body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2022-09-08", "2022-9-7", "20220907", "2022-09-07T00:00:00"})
    void 실패_scan_date_가_보낸_날짜와_다르면(String v) {
        assertBad(with(b -> b.put("scan_date", v)), "scan_date");
    }

    @Test
    void 실패_snapshot_sha256_모양() {
        assertBad(with(b -> b.put("snapshot_sha256", "A".repeat(64))), "snapshot_sha256");
        assertBad(with(b -> b.put("snapshot_sha256", "a".repeat(63))), "snapshot_sha256");
        assertBad(with(b -> b.put("snapshot_sha256", "g".repeat(64))), "snapshot_sha256");
        assertBad(with(b -> b.put("snapshot_sha256", 1)), "snapshot_sha256");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ml_model", "dl_model"})
    void 실패_모델_이름이_빈_값이거나_문자열이_아니면(String field) {
        assertBad(with(b -> b.put(field, "")), field);
        assertBad(with(b -> b.put(field, "   ")), field);
        assertBad(with(b -> b.put(field, 3)), field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidence_transactions", "scored_transactions", "linked_alerts", "ml_only_alerts",
            "unscored_alerts"})
    void 실패_건수_칸이_음수_실수_문자열이면(String field) {
        assertBad(with(b -> b.put(field, -1)), field);
        assertBad(with(b -> b.put(field, 1.5)), field);
        assertBad(with(b -> b.put(field, "3")), field);
    }

    @Test
    void 실패_scored_transactions_가_evidence_transactions_보다_크면() {
        assertBad(with(b -> b.put("scored_transactions", 11)), "scored_transactions");
    }

    @Test
    void 실패_alerts_가_비었거나_배열이_아니거나_원소가_객체가_아니면() {
        assertBad(with(b -> {
            b.putArray("alerts");
            b.put("linked_alerts", 0);
            b.put("ml_only_alerts", 0);
            b.put("unscored_alerts", 0);
        }), "alerts");
        assertBad(with(b -> b.put("alerts", "x")), "alerts");
        assertBad(with(b -> b.withArray("alerts").set(0, OM.getNodeFactory().textNode("x"))), "alerts");
    }

    // --- 경보 번호·룰 -----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ALT-20220908-000001", "ALT-20220907-00001", "ALT-20220907-00000a", "alt-20220907-000001",
            "ALT-20220907-000001 ", "ALT-20220907000001", "123"})
    void 실패_alert_id_날짜가_다르거나_모양이_틀리면(String v) {
        assertBad(with(b -> at(b, 0).put("alert_id", v)), "alert_id");
    }

    @Test
    void 실패_alert_id_가_겹치거나_없으면() {
        assertBad(with(b -> at(b, 5).put("alert_id", DlRankingExamples.alertNo(DATE, 4))), "alert_id");
        assertBad(with(b -> at(b, 0).remove("alert_id")), "alert_id");
        assertBad(with(b -> at(b, 0).put("alert_id", 1)), "alert_id");
    }

    @Test
    void 실패_rule_id_가_빈_값이거나_없으면() {
        assertBad(with(b -> at(b, 0).put("rule_id", "")), "rule_id");
        assertBad(with(b -> at(b, 0).put("rule_id", " ")), "rule_id");
        assertBad(with(b -> at(b, 0).remove("rule_id")), "rule_id");
    }

    // --- 근거 수 -----------------------------------------------------------------

    @Test
    void 실패_evidence_count_가_0_이하이면_AI_보다_엄격() {
        assertBad(with(b -> {
            at(b, 5).put("evidence_count", 0);
            at(b, 5).put("dl_linked", 0);
        }), "evidence_count");
    }

    @Test
    void 실패_dl_linked_가_범위_밖이면() {
        assertBad(with(b -> at(b, 1).put("dl_linked", 4)), "dl_linked");
        assertBad(with(b -> at(b, 1).put("dl_linked", -1)), "dl_linked");
        assertBad(with(b -> at(b, 1).put("dl_linked", 1.5)), "dl_linked");
    }

    // --- 점수 -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1.1", "-0.1", "1e400", "\"0.5\""})
    void 실패_ml_score_가_범위_밖이거나_무한이거나_숫자가_아니면(String raw) throws Exception {
        JsonNode v = OM.readTree(raw);
        assertBad(with(b -> at(b, 0).set("ml_score", v)), "ml_score");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "-1", "1e400", "true"})
    void 실패_dl_score_가_범위_밖이거나_무한이거나_숫자가_아니면(String raw) throws Exception {
        JsonNode v = OM.readTree(raw);
        assertBad(with(b -> at(b, 0).set("dl_score", v)), "dl_score");
        assertBad(with(b -> at(b, 4).set("dl_score", v)), "dl_score");
    }

    @Test
    void 실패_점수_칸이_아예_없으면() {
        assertBad(with(b -> at(b, 0).remove("ml_score")), "ml_score");
        assertBad(with(b -> at(b, 0).remove("dl_score")), "dl_score");
    }

    // --- 이유 · ML ---------------------------------------------------------------

    @Test
    void 실패_모르는_fallback_reason() {
        assertBad(with(b -> at(b, 1).put("fallback_reason", "OTHER")), "fallback_reason");
        assertBad(with(b -> at(b, 1).put("fallback_reason", "")), "fallback_reason");
        assertBad(with(b -> at(b, 1).remove("fallback_reason")), "fallback_reason");
    }

    @Test
    void 실패_ml_score_null_인데_이유가_ML_UNSCORED_가_아니면() {
        assertBad(with(b -> {
            at(b, 0).putNull("ml_score");
            at(b, 0).put("ml_failure_code", "COMPUTE_ERROR");
        }), "fallback_reason");
    }

    @Test
    void 실패_ML_UNSCORED_인데_ml_score_가_있으면() {
        assertBad(with(b -> {
            at(b, 4).put("ml_score", 0.1);
            at(b, 4).putNull("ml_failure_code");
        }), "fallback_reason");
    }

    @Test
    void 실패_ML_UNSCORED_인데_순위가_있으면() {
        assertBad(with(b -> at(b, 4).put("ml_rank", 5)), "ml_rank");
        assertBad(with(b -> at(b, 4).put("fused_rank", 5)), "fused_rank");
    }

    @Test
    void 실패_ML_점수_있는데_순위가_없으면() {
        assertBad(with(b -> at(b, 0).putNull("ml_rank")), "ml_rank");
        assertBad(with(b -> at(b, 0).putNull("fused_rank")), "fused_rank");
    }

    @Test
    void 실패_ml_failure_code_규칙() {
        assertBad(with(b -> at(b, 0).put("ml_failure_code", "COMPUTE_ERROR")), "ml_failure_code");   // 점수 있음 + 코드
        assertBad(with(b -> at(b, 4).putNull("ml_failure_code")), "ml_failure_code");             // 점수 없음 + 코드 없음
        assertBad(with(b -> at(b, 4).put("ml_failure_code", "")), "ml_failure_code");
        assertBad(with(b -> at(b, 4).put("ml_failure_code", 1)), "ml_failure_code");
        assertBad(with(b -> at(b, 4).remove("ml_failure_code")), "ml_failure_code");
    }

    // --- DL 연결 -----------------------------------------------------------------

    @Test
    void 실패_ML_있는_경보_이유_null_인데_dl_score_없거나_근거가_덜_연결되면() {
        assertBad(with(b -> at(b, 0).putNull("dl_score")), "dl_score");
        assertBad(with(b -> at(b, 0).put("dl_linked", 1)), "dl_linked");
    }

    @Test
    void 실패_INCOMPLETE_인데_dl_score_가_있거나_근거가_다_연결되면() {
        assertBad(with(b -> at(b, 1).put("dl_score", 0.3)), "dl_score");
        assertBad(with(b -> at(b, 1).put("dl_linked", 3)), "dl_linked");
    }

    @Test
    void 실패_ML_UNSCORED_는_dl_score_있음과_근거_다_연결이_같이_가야_한다() {
        assertBad(with(b -> at(b, 4).put("dl_linked", 1)), "dl_linked");    // dl_score 있음 + 덜 연결
        assertBad(with(b -> at(b, 5).put("dl_linked", 1)), "dl_linked");    // dl_score 없음 + 다 연결
    }

    // --- 순위 -----------------------------------------------------------------

    @Test
    void 실패_ml_rank_빠짐_겹침_범위밖() {
        assertBad(with(b -> at(b, 2).put("ml_rank", 5)), "ml_rank");   // 1,2,5,3 → 4 빠짐
        assertBad(with(b -> at(b, 2).put("ml_rank", 3)), "ml_rank");   // 3 겹침
        assertBad(with(b -> at(b, 2).put("ml_rank", 0)), "ml_rank");
        assertBad(with(b -> at(b, 2).put("ml_rank", "4")), "ml_rank");
        assertBad(with(b -> at(b, 2).remove("ml_rank")), "ml_rank");
    }

    @Test
    void 실패_fused_rank_겹침() {
        assertBad(with(b -> at(b, 3).put("fused_rank", 3)), "fused_rank");
    }

    @Test
    void 실패_INCOMPLETE_인데_ml_rank_와_fused_rank_가_다르면() {
        ObjectNode b = valid(DATE);
        at(b, 0).put("fused_rank", 2);   // 1번과 2번(INCOMPLETE) 결합 순위를 맞바꿈 — 1…k 는 그대로
        at(b, 1).put("fused_rank", 1);
        ArrayNode alerts = b.withArray("alerts");
        JsonNode first = alerts.get(0);
        alerts.set(0, alerts.get(1));
        alerts.set(1, first);
        assertBad(b, "fused_rank");
    }

    // --- 배열 순서 -------------------------------------------------------------

    @Test
    void 실패_배열이_fused_rank_순서가_아니면() {
        ObjectNode b = valid(DATE);
        ArrayNode alerts = b.withArray("alerts");
        JsonNode third = alerts.get(2);
        alerts.set(2, alerts.get(3));
        alerts.set(3, third);
        assertBad(b, "alerts");
    }

    @Test
    void 실패_ML_없는_경보가_번호_순이_아니거나_앞에_오면() {
        ObjectNode swapped = valid(DATE);
        ArrayNode a = swapped.withArray("alerts");
        JsonNode fifth = a.get(4);
        a.set(4, a.get(5));
        a.set(5, fifth);
        assertBad(swapped, "alerts");

        ObjectNode front = valid(DATE);
        ArrayNode f = front.withArray("alerts");
        JsonNode unscored = f.remove(4);
        f.insert(0, unscored);
        assertBad(front, "alerts");
    }

    // --- 건수 -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"linked_alerts", "ml_only_alerts", "unscored_alerts"})
    void 실패_건수_칸이_이유별_수와_다르면(String field) {
        assertBad(with(b -> b.put(field, b.get(field).intValue() + 1)), field);
    }

    // --- 검토 1 회귀 -----------------------------------------------------------

    @Test
    void 검토1_H1_ml_rank_를_맞바꾸면_ML_점수_순서와_달라_실패() {
        assertBad(with(b -> {
            at(b, 2).put("ml_rank", 3);   // 000006(0.6) 이 3위, 000003(0.7) 이 4위 — 1…k 는 그대로
            at(b, 3).put("ml_rank", 4);
        }), "ml_rank");
    }

    @Test
    void 검토1_H1_ML_동점이면_번호_작은_쪽이_위() {
        assertThatCode(() -> check(with(b -> at(b, 2).put("ml_score", 0.7)))).doesNotThrowAnyException();   // 000003 3위 · 000006 4위
        assertBad(with(b -> {
            at(b, 2).put("ml_score", 0.7);
            at(b, 2).put("ml_rank", 3);
            at(b, 3).put("ml_rank", 4);
        }), "ml_rank");
    }

    @Test
    void 검토1_H1_음수_0과_0은_같은_점수로_본다() {
        ObjectNode b = valid(DATE);
        at(b, 2).put("ml_score", 0.0);    // 000006 ml_rank 4
        at(b, 3).put("ml_score", -0.0);   // 000003 ml_rank 3 — 같은 점수라 번호 순. Double.compare 로 보면 0.0 이 위라 거꾸로 됨
        assertThatCode(() -> check(b)).doesNotThrowAnyException();
    }

    @Test
    void 검토1_M4_경보_근거_수가_전체_거래_수를_넘으면_실패() {
        assertBad(with(b -> {
            b.put("evidence_transactions", 0);
            b.put("scored_transactions", 0);
        }), "evidence_count");
        assertBad(with(b -> {
            b.put("evidence_transactions", 2);   // 000002 근거 3
            b.put("scored_transactions", 2);
        }), "evidence_count");
    }

    @Test
    void 검토1_M4_경보_연결_수가_DL_점수_거래_수를_넘으면_실패() {
        assertBad(with(b -> b.put("scored_transactions", 1)), "dl_linked");   // 000001 연결 2
        assertThatCode(() -> check(with(b -> b.put("scored_transactions", 2)))).doesNotThrowAnyException();
    }

    // --- SCRUM-415 근거 신원 칸 (AI #108 뒤 필수) ------------------------------

    @Test
    void 정상_근거_신원_두_칸이_있으면_통과하고_자정_초_0_도_통과() {
        assertThatCode(() -> check(with(b -> at(b, 0).put("reference_ts", "2022-09-07T00:00:00"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> check(with(b -> at(b, 0).put("reference_ts", "2022-09-06T23:59:59"))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference_ts", "evidence_digest"})
    void 실패_근거_신원_칸이_없거나_null_이거나_문자열이_아니면(String field) {
        assertBad(with(b -> at(b, 3).remove(field)), field);
        assertBad(with(b -> at(b, 3).putNull(field)), field);
        assertBad(with(b -> at(b, 3).put(field, 20220907)), field);
        assertBad(with(b -> at(b, 3).put(field, "")), field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2022-09-07 00:29:00", "2022-09-07T00:29", "2022-09-07T00:29:00Z",
            "2022-09-07T00:29:00+09:00", "2022-09-07T00:29:00.000", "2022-9-7T00:29:00", "2022-13-40T00:00:00",
            "2022-09-07T24:00:00", "2022-02-30T00:00:00", " 2022-09-07T00:29:00", "2022-09-07T00:29:00\n",
            "２０２２-09-07T00:29:00"})
    void 실패_reference_ts_가_T_모양_초까지가_아니거나_없는_날짜면(String v) {
        assertBad(with(b -> at(b, 2).put("reference_ts", v)), "reference_ts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"upper", "63", "65", "nonhex", "space"})
    void 실패_evidence_digest_가_소문자_16진_64자가_아니면(String kind) {
        String good = "d277a2cdcb9bd308cd75c3cf55b4cb15f0a99e5cb894728bffe1c610960caade";
        String v = switch (kind) {
            case "upper" -> good.toUpperCase();
            case "63" -> good.substring(1);
            case "65" -> good + "0";
            case "nonhex" -> "g" + good.substring(1);
            default -> good.substring(1) + " ";
        };
        assertBad(with(b -> at(b, 1).put("evidence_digest", v)), "evidence_digest");
    }

    @Test
    void 근거_신원_칸_실패_메시지에는_값이_없다() {
        Throwable e = catchThrowable(() -> check(with(b -> at(b, 0).put("reference_ts", "2022-09-07 00:29:00"))));
        assertThat(e).hasMessageNotContaining("00:29");
        Throwable d = catchThrowable(() -> check(with(b -> at(b, 0).put("evidence_digest", "ABCDEF"))));
        assertThat(d).hasMessageNotContaining("ABCDEF");
    }
}
