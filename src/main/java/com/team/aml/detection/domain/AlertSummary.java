package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 케이스 상세 화면용 경보 한 건 (계약서 C2 alert·latest_score, SCRUM-152). cases GetCaseDetail 이 부른다.
 * score_band·policy_version 은 없다 — 등급은 cases CasePolicy 몫이고 케이스 생성 때 cases 가 저장한다 (안건 2, 계약서 Q2).
 *
 * @param transactionCount 경보에 묶인 거래 수 (alert_transactions)
 */
public record AlertSummary(String alertNo, String ruleId, LocalDate batchDate, LocalDateTime windowStart,
                           LocalDateTime windowEnd, LocalDateTime referenceTs, int transactionCount, Score score) {

    /**
     * 경보의 점수 (alerts.score_status + alert_scores). 점수가 없으면(PENDING·FAILED) score·버전·data_quality_status 는 null.
     *
     * @param status      PENDING · SCORED · FAILED
     * @param failureCode FAILED 일 때 AI 실패 코드. 묶음 실패면 FAILED 여도 null (미결 16)
     * @param reasons     점수 응답의 reasons 그대로. 없으면 [] (null 아님, 계약서 2.3)
     */
    public record Score(String status, BigDecimal score, String failureCode, String modelVersion,
                        String featureVersion, String dataQualityStatus, JsonNode reasons) {

        /** reasons 가 1개 이상이면 PROVIDED, 아니면 NOT_PROVIDED. 〔제안 — 지윤 확인 전〕 */
        public String reasonsStatus() {
            return reasons.isEmpty() ? "NOT_PROVIDED" : "PROVIDED";
        }
    }
}
