package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.ai.AiDtos.AlertPayload;

/**
 * STR 초안 요청에 넣을 경보 한 건 (규약 §5.1, SCRUM-155). str RequestStrDraft 가 그대로 StrDraftRequest 에 담는다.
 * evidence_transaction_ids 는 alert.transactionIds 전체다 (안건 23).
 *
 * @param alert           규약 §2 경보 JSON (created_at 포함)
 * @param reasons         점수 응답의 reasons 그대로. 점수가 아직 없으면 null
 * @param priorAlertCount 같은 법인에서 이 경보 reference_ts 보다 앞선 경보 수. 법인이 없으면 0
 */
public record AlertForDraft(AlertPayload alert, JsonNode reasons, int priorAlertCount) {}
