package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.AskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-291 — 질문 답변 응답 확인 `requireAskResponse` (규약 5.4 상태별 칸 규칙 + 보낸 값 대조).
 * 계약 예시(상태 4종)를 기준으로 칸 하나씩 바꿔 본다. 위반은 모두 502 `AI_BAD_RESPONSE`, 메시지엔 칸 이름만.
 */
class AskResponseCheckTest {

    private static final Path EXAMPLES = Path.of("contracts/examples");
    private static final ObjectMapper OM = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static JsonNode example(String name) {
        try {
            return OM.readTree(Files.readString(EXAMPLES.resolve(name)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 보낸 요청 = `ask_request.json` (근거 104221·104233·104301, 케이스 CASE-2022-000057). */
    private static AskRequest sent() {
        try {
            return OM.treeToValue(example("ask_request.json"), AskRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode body(String status) {
        return (ObjectNode) example("ask_response_" + status + ".json").deepCopy();
    }

    // --- 정상 ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"answered", "no_data", "unverified", "out_of_scope"})
    void 정상_계약_예시_상태_4종은_통과한다(String status) {
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), body(status))).doesNotThrowAnyException();
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_ANSWERED의_code_checks와_evidence_ids는_빈_목록이어도_통과한다() {
        ObjectNode b = body("answered");
        b.putArray("code_checks");
        b.putArray("evidence_ids");
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_answer_앞뒤_공백과_모르는_칸은_그대로_받는다() {
        ObjectNode b = body("answered");
        b.put("answer", "  입금 상대 계좌는 3개예요. ");
        b.put("새_칸", 1);
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_evidence_ids가_보낸_근거의_일부면_통과한다() {
        ObjectNode b = body("answered");
        b.putArray("evidence_ids").add(104233L);
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_answer는_NFC_뒤_UTF16_400까지_통과하고_분해형은_합친_길이로_센다() {
        ObjectNode b = body("answered");
        b.put("answer", "가".repeat(400));
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
        b.put("answer", "e\u0301".repeat(400));   // 800 코드 단위지만 NFC 뒤 é 400개
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_code_check_section이_서술_섹션_이름이면_통과하고_reason_칸이_없어도_match_true면_통과한다() {
        ObjectNode b = body("answered");
        ObjectNode check = (ObjectNode) b.get("code_checks").get(0);
        check.put("section", "SUBJECT");
        check.remove("reason");
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_bundles_3개까지_통과한다() {
        ObjectNode b = body("answered");
        ((ObjectNode) b.get("generation_meta")).putArray("bundles").add("a").add("b").add("c");
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }

    // --- 실패 ---------------------------------------------------------------

    static Stream<Arguments> violations() {
        return Stream.of(
                bad("answered", "produced_at", b -> b.remove("produced_at")),
                bad("answered", "case_no", b -> b.remove("case_no")),
                bad("answered", "generation_id", b -> b.putNull("generation_id")),
                bad("answered", "status", b -> b.remove("status")),
                bad("answered", "generation_meta", b -> b.remove("generation_meta")),
                bad("answered", "cites", b -> b.remove("cites")),
                bad("answered", "evidence_ids", b -> b.putNull("evidence_ids")),
                bad("answered", "code_checks", b -> b.put("code_checks", "[]")),
                bad("answered", "status", b -> b.put("status", "PARTIAL")),
                bad("answered", "kind", b -> b.put("kind", "HOW")),
                bad("out_of_scope", "out_of_scope_reason", b -> b.put("out_of_scope_reason", "RUDE")),
                bad("answered", "case_no", b -> b.put("case_no", "CASE-2022-000058")),
                bad("answered", "produced_at", b -> b.put("produced_at", "2026-11-27T11:10:00+09:00")),
                bad("answered", "produced_at", b -> b.put("produced_at", "2026-13-27T02:10:00Z")),
                // ANSWERED
                bad("answered", "answer", b -> b.putNull("answer")),
                bad("answered", "answer", b -> b.put("answer", "   ")),
                bad("answered", "answer", b -> b.put("answer", 3)),
                bad("answered", "cites", b -> b.putArray("cites")),
                bad("answered", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("match", false)),
                // NO_DATA · UNVERIFIED · OUT_OF_SCOPE 는 answer·cites·evidence_ids 비움
                bad("no_data", "answer", b -> b.put("answer", "없어요")),
                bad("no_data", "cites", b -> b.putArray("cites").add("subject")),
                bad("unverified", "evidence_ids", b -> b.putArray("evidence_ids").add(104221L)),
                bad("no_data", "code_checks", b -> b.putArray("code_checks").addObject().put("match", true)),
                bad("out_of_scope", "code_checks", b -> b.putArray("code_checks").addObject().put("match", false)),
                bad("unverified", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("match", true)),
                bad("unverified", "code_checks", b -> b.putArray("code_checks")),
                // kind · 사유 짝
                bad("out_of_scope", "kind", b -> b.put("kind", "FACT")),
                bad("out_of_scope", "out_of_scope_reason", b -> b.putNull("out_of_scope_reason")),
                bad("no_data", "kind", b -> b.putNull("kind")),
                bad("answered", "out_of_scope_reason", b -> b.put("out_of_scope_reason", "JUDGMENT")),
                // evidence_ids — 보낸 근거 안, 1 이상 정수
                bad("answered", "evidence_ids", b -> b.putArray("evidence_ids").add(104221L).add(999999L)),
                bad("answered", "evidence_ids", b -> b.putArray("evidence_ids").add(0)),
                bad("answered", "evidence_ids", b -> b.putArray("evidence_ids").add("104221")),
                // prompt_version 짝
                bad("out_of_scope", "generation_meta.prompt_version",
                        b -> ((ObjectNode) b.get("generation_meta")).put("prompt_version", "ask_answer_v1")),
                bad("answered", "generation_meta.prompt_version",
                        b -> ((ObjectNode) b.get("generation_meta")).putNull("prompt_version")),
                bad("no_data", "generation_meta.prompt_version",
                        b -> ((ObjectNode) b.get("generation_meta")).remove("prompt_version")),
                bad("out_of_scope", "generation_meta.prompt_version",
                        b -> ((ObjectNode) b.get("generation_meta")).remove("prompt_version")),   // 칸은 있어야 함
                // 2차 — ask.py 와 맞춤: 칸 타입
                bad("answered", "case_no", b -> b.put("case_no", 57)),
                bad("answered", "generation_id", b -> b.put("generation_id", 91)),
                // cites 원소 (ask.py: 공백만·중복 거부, 공백은 자른 뒤 비교)
                bad("answered", "cites", b -> b.putArray("cites").addNull()),
                bad("answered", "cites", b -> b.putArray("cites").add("   ")),
                bad("answered", "cites", b -> b.putArray("cites").add("\u00a0")),
                bad("answered", "cites", b -> b.putArray("cites").add(1)),
                bad("answered", "cites", b -> b.putArray("cites").add("counterparties.count").add(" counterparties.count ")),
                // answer 길이 — NFC 뒤 UTF-16 400 초과
                bad("answered", "answer", b -> b.put("answer", "가".repeat(401))),
                bad("answered", "answer", b -> b.put("answer", "😀".repeat(201))),   // 402 코드 단위
                // code_checks 원소 (verify.py CodeCheck)
                bad("answered", "code_checks", b -> b.putArray("code_checks").add("COUNT_MATCH")),
                bad("answered", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("check", "SUM_MATCH")),
                bad("answered", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("section", "INTRO")),
                bad("answered", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).remove("draft_value")),
                bad("answered", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("source_value", 3)),
                bad("answered", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("reason", "MISMATCH")),
                bad("unverified", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).putNull("reason")),
                bad("unverified", "code_checks", b -> ((ObjectNode) b.get("code_checks").get(0)).put("reason", "WRONG")),
                // generation_meta 칸 (ask.py AskGenerationMeta)
                bad("answered", "generation_meta.llm_model", b -> ((ObjectNode) b.get("generation_meta")).remove("llm_model")),
                bad("answered", "generation_meta.plan_prompt_version",
                        b -> ((ObjectNode) b.get("generation_meta")).putNull("plan_prompt_version")),
                bad("answered", "generation_meta.server_version", b -> ((ObjectNode) b.get("generation_meta")).put("server_version", 1)),
                bad("answered", "generation_meta.tokens_in", b -> ((ObjectNode) b.get("generation_meta")).put("tokens_in", "1450")),
                bad("answered", "generation_meta.latency_ms", b -> ((ObjectNode) b.get("generation_meta")).remove("latency_ms")),
                bad("answered", "generation_meta.bundles", b -> ((ObjectNode) b.get("generation_meta")).remove("bundles")),
                bad("answered", "generation_meta.bundles", b -> ((ObjectNode) b.get("generation_meta")).putArray("bundles")),
                bad("answered", "generation_meta.bundles",
                        b -> ((ObjectNode) b.get("generation_meta")).putArray("bundles").add("a").add("b").add("c").add("d")),
                bad("answered", "generation_meta.bundles", b -> ((ObjectNode) b.get("generation_meta")).putArray("bundles").add(" ")),
                bad("answered", "generation_meta.bundles", b -> ((ObjectNode) b.get("generation_meta")).putArray("bundles").add("a").add("a")),
                bad("out_of_scope", "generation_meta.bundles",
                        b -> ((ObjectNode) b.get("generation_meta")).putArray("bundles").add("subject")));
    }

    private static Arguments bad(String status, String field, Consumer<ObjectNode> edit) {
        return Arguments.of(status, field, edit);
    }

    @ParameterizedTest(name = "[{index}] {0} 의 {1}")
    @MethodSource("violations")
    void 실패_규칙을_어기면_AI_BAD_RESPONSE_502이고_메시지는_칸_이름만(String status, String field,
                                                             Consumer<ObjectNode> edit) {
        ObjectNode b = body(status);
        String answer = b.path("answer").asText("");
        edit.accept(b);

        Throwable e = catchThrowable(() -> WebClientAiClient.requireAskResponse(sent(), b));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).contains(field);
            if (!answer.isEmpty()) assertThat(ae.getMessage()).doesNotContain(answer);   // 답 문장은 메시지에 없음
        });
    }

    // --- 추가 검토 2 — 공백 판정 = 파이썬 str.isspace() 29글자 ------------------------------

    /** AI 서버 Python 3.12 의 `[c for c in range(0x110000) if chr(c).isspace()]` 결과 (11/19 확인). */
    static final int[] PYTHON_SPACES = {0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000};

    @Test
    void 정상_공백_판정은_BMP_전체에서_파이썬_29글자와_정확히_같다() {
        java.util.Set<Integer> want = java.util.Arrays.stream(PYTHON_SPACES).boxed().collect(java.util.stream.Collectors.toSet());
        for (int c = 0; c <= 0xFFFF; c++) {
            assertThat(WebClientAiClient.isPythonSpace((char) c)).as("U+%04X", c).isEqualTo(want.contains(c));
        }
    }

    static Stream<Arguments> spaceInEachPlace() {
        return java.util.Arrays.stream(PYTHON_SPACES).boxed().flatMap(c -> Stream.of(
                Arguments.of(c, "answer"), Arguments.of(c, "cites"), Arguments.of(c, "generation_meta.bundles")));
    }

    @ParameterizedTest(name = "U+{0} 만 든 {1}")
    @MethodSource("spaceInEachPlace")
    void 실패_파이썬_공백_한_글자만_든_answer_cites_bundles_원소는_거부한다(int codePoint, String place) {
        String space = String.valueOf((char) codePoint);
        ObjectNode b = body("answered");
        switch (place) {
            case "answer" -> b.put("answer", space);
            case "cites" -> b.putArray("cites").add(space);
            default -> ((ObjectNode) b.get("generation_meta")).putArray("bundles").add(space);
        }
        Throwable e = catchThrowable(() -> WebClientAiClient.requireAskResponse(sent(), b));
        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.getMessage()).contains(place);
        });
    }

    @Test
    void 실패_NEL을_뺀_값이_같은_cites는_중복으로_거부한다() {
        ObjectNode b = body("answered");
        b.putArray("cites").add("a" + (char) 0x85).add("a");
        assertThat(catchThrowable(() -> WebClientAiClient.requireAskResponse(sent(), b)))
                .isInstanceOfSatisfying(AiClientException.class, ae -> assertThat(ae.getMessage()).contains("cites"));
    }

    @Test
    void 경계_제로폭_공백_U200B는_파이썬_공백이_아니라_통과한다() {
        String zwsp = String.valueOf((char) 0x200B);
        ObjectNode b = body("answered");
        b.put("answer", zwsp);
        b.putArray("cites").add(zwsp);
        ((ObjectNode) b.get("generation_meta")).putArray("bundles").add(zwsp);
        assertThatCode(() -> WebClientAiClient.requireAskResponse(sent(), b)).doesNotThrowAnyException();
    }
}
