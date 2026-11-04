package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 초안 요청 기록 단위 테스트 (스프링 없이). V008 str_draft_requests 의 상태 규칙과 같다. */
class StrDraftRequestTest {

    private static final Instant AT = Instant.parse("2026-11-04T05:00:00Z");

    private static JsonNode snapshot() {
        return new ObjectMapper().createObjectNode().put("draft_id", "DRF-000312");
    }

    private static StrDraftRequest newRequest() {
        return StrDraftRequest.request(312L, 10L, snapshot());
    }

    // 1
    @Test
    void 새_요청은_REQUESTED이고_끝난_시각이_없다() {
        StrDraftRequest request = newRequest();

        assertThat(request.getId()).isEqualTo(312L);
        assertThat(request.getCaseId()).isEqualTo(10L);
        assertThat(request.getStatus()).isEqualTo(StrDraftRequestStatus.REQUESTED);
        assertThat(request.isRequested()).isTrue();
        assertThat(request.getFinishedAt()).isNull();
        assertThat(request.isNew()).isTrue();          // 첫 save() 는 INSERT (AssignedIdEntity)
    }

    // 2
    @Test
    void 성공_실패_만료는_끝난_시각과_함께_한_번만_바뀐다() {
        StrDraftRequest succeeded = newRequest();
        succeeded.succeed(AT);
        assertThat(succeeded.getStatus()).isEqualTo(StrDraftRequestStatus.SUCCEEDED);
        assertThat(succeeded.getFinishedAt()).isEqualTo(AT);

        StrDraftRequest failed = newRequest();
        failed.fail(AT);
        assertThat(failed.getStatus()).isEqualTo(StrDraftRequestStatus.FAILED);

        StrDraftRequest expired = newRequest();
        expired.expire(AT);
        assertThat(expired.getStatus()).isEqualTo(StrDraftRequestStatus.EXPIRED);
    }

    // 3
    @Test
    void 이미_끝난_요청을_또_끝내면_거부하고_상태는_그대로다() {
        StrDraftRequest request = newRequest();
        request.succeed(AT);

        assertThatThrownBy(() -> request.fail(AT.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.succeed(AT.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(StrDraftRequestStatus.SUCCEEDED);
        assertThat(request.getFinishedAt()).isEqualTo(AT);
    }

    // 4
    @Test
    void 요청_스냅샷은_복사본이라_밖에서_바꿔도_그대로다() {
        JsonNode original = snapshot();
        StrDraftRequest request = StrDraftRequest.request(312L, 10L, original);

        ((ObjectNode) original).put("draft_id", "DRF-999999");
        ((ObjectNode) request.getRequestSnapshot()).put("draft_id", "DRF-888888");

        assertThat(request.getRequestSnapshot()).isEqualTo(snapshot());   // 재전송은 처음 보낸 그대로
    }

    // 5
    @Test
    void 번호_케이스_스냅샷이_없으면_만들지_않는다() {
        assertThatThrownBy(() -> StrDraftRequest.request(null, 10L, snapshot())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrDraftRequest.request(312L, null, snapshot())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StrDraftRequest.request(312L, 10L, null)).isInstanceOf(NullPointerException.class);
    }
}
