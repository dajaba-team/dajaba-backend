package com.team.aml.shared.messaging;

/** STR 초안 생성 작업(S1 비동기). 메시지에는 draft_id 만 — 요청 본문·상태는 str_draft_requests 에서 읽는다. */
public record StrDraftMessage(long draftId) {
}
