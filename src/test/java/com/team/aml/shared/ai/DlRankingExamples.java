package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * SCRUM-376 — ML+DL 조사 순위 응답 예시(AI `dl_ranking.py` 계산 규칙대로 손으로 맞춘 6건). 테스트끼리 같이 쓴다.
 *
 * <pre>
 *  번호      룰   ml    dl    근거/연결  이유                      ml_rank fused_rank
 *  000001    R01  0.9   0.2   2/2       null                      1       1
 *  000002    R02  0.8   null  3/1       INCOMPLETE_DL_EVIDENCE    2       2   (자리 유지)
 *  000006    R03  0.6   0.99  1/1       null                      4       3
 *  000003    R03  0.7   0.5   1/1       null                      3       4
 *  000004    R01  null  0.5   2/2       ML_UNSCORED (DL 은 있음)  null    null
 *  000005    R02  null  null  1/0       ML_UNSCORED               null    null
 * </pre>
 * 완전 연결 {1,3,6} 의 내부 순위(오름차순) — ML: 6→1, 3→2, 1→3 / DL: 1→1, 3→2, 6→3. max: 1→3, 6→3, 3→2 →
 * 결합값 내림차순·번호 오름차순으로 1, 6, 3 을 그 자리(1·3·4번째)에 둔다. 배열은 fused 순서 다음 ML 없는 경보 번호 순.
 */
public final class DlRankingExamples {

    public static final String SHA = "a".repeat(64);
    public static final String ML_MODEL = "xgb-hi-medium-r04-event-c2-31-v1";
    public static final String DL_MODEL = "HI-Medium-B-GINe-counts-best-13";

    private DlRankingExamples() {}

    public static String alertNo(LocalDate date, int seq) {
        return "ALT-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", seq);
    }

    /** 위 표 그대로의 응답 몸통. */
    public static ObjectNode valid(LocalDate date) {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        b.put("scan_date", date.toString());
        b.put("snapshot_sha256", SHA);
        b.put("ml_model", ML_MODEL);
        b.put("dl_model", DL_MODEL);
        b.put("evidence_transactions", 10);
        b.put("scored_transactions", 9);
        b.put("linked_alerts", 3);
        b.put("ml_only_alerts", 1);
        b.put("unscored_alerts", 2);
        ArrayNode alerts = b.putArray("alerts");
        alerts.add(alert(date, 1, "R01", 0.9, null, 0.2, 2, 2, null, 1, 1));
        alerts.add(alert(date, 2, "R02", 0.8, null, null, 3, 1, "INCOMPLETE_DL_EVIDENCE", 2, 2));
        alerts.add(alert(date, 6, "R03", 0.6, null, 0.99, 1, 1, null, 4, 3));
        alerts.add(alert(date, 3, "R03", 0.7, null, 0.5, 1, 1, null, 3, 4));
        alerts.add(alert(date, 4, "R01", null, "TRANSACTION_NOT_FOUND", 0.5, 2, 2, "ML_UNSCORED", null, null));
        alerts.add(alert(date, 5, "R02", null, "COMPUTE_ERROR", null, 1, 0, "ML_UNSCORED", null, null));
        return b;
    }

    public static ObjectNode alert(LocalDate date, int seq, String rule, Double ml, String mlFailure, Double dl,
                                   int evidence, int linked, String reason, Integer mlRank, Integer fusedRank) {
        ObjectNode a = JsonNodeFactory.instance.objectNode();
        a.put("alert_id", alertNo(date, seq));
        a.put("rule_id", rule);
        a.put("ml_score", ml);
        a.put("ml_failure_code", mlFailure);
        a.put("dl_score", dl);
        a.put("evidence_count", evidence);
        a.put("dl_linked", linked);
        a.put("fallback_reason", reason);
        a.put("ml_rank", mlRank);
        a.put("fused_rank", fusedRank);
        return a;
    }

    /** 배열 {@code i} 번째 경보(0부터). */
    public static ObjectNode at(ObjectNode body, int i) {
        return (ObjectNode) body.withArray("alerts").get(i);
    }
}
