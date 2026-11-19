package com.team.aml.shared.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequestIdFilter — 받는 X-Request-Id 는 1~64자 [A-Za-z0-9._-] 만 그대로 (SCRUM-272, 03_shared §8, AI 와 같은 64).
 * 없거나 빈 값이면 조용히 새로 만들고, 그 밖은 새 UUID + `request_id_replaced received_len=N` 경고(원래 값은 안 찍음).
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestIdFilterTest {

    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();
    private final List<String> seenInChain = new ArrayList<>();
    private MockHttpServletResponse res;

    @AfterEach
    void clear() {
        MDC.clear();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_허용_헤더는_MDC와_응답_헤더에_그대로_쓰고_경고가_없다(CapturedOutput output) throws Exception {
        run("rid-Front_01.abc");

        assertThat(seenInChain).containsExactly("rid-Front_01.abc");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("rid-Front_01.abc");
        assertThat(replacedLines(output)).isEmpty();
    }

    @Test
    void 정상_요청이_끝나면_MDC의_request_id와_user_id를_지운다() throws Exception {
        FilterChain chain = (rq, rs) -> MDC.put("user_id", "7");
        res = new MockHttpServletResponse();

        filter.doFilter(request("rid-clear"), res, chain);

        assertThat(MDC.get("request_id")).isNull();
        assertThat(MDC.get("user_id")).isNull();
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_64자는_그대로(CapturedOutput output) throws Exception {
        String id = "a".repeat(64);

        run(id);

        assertThat(seenInChain).containsExactly(id);
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
        assertThat(replacedLines(output)).isEmpty();
    }

    @Test
    void 경계_1자는_그대로(CapturedOutput output) throws Exception {
        run("x");

        assertThat(seenInChain).containsExactly("x");
        assertThat(replacedLines(output)).isEmpty();
    }

    @Test
    void 경계_헤더가_없으면_새로_만들고_경고가_없다(CapturedOutput output) throws Exception {
        run(null);

        assertThat(seenInChain.get(0)).matches(UUID_RE);
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenInChain.get(0));
        assertThat(replacedLines(output)).isEmpty();
    }

    @Test
    void 경계_빈_헤더는_없을_때처럼_새로_만들고_경고가_없다(CapturedOutput output) throws Exception {
        run("");

        assertThat(seenInChain.get(0)).matches(UUID_RE);
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenInChain.get(0));
        assertThat(replacedLines(output)).isEmpty();
    }

    @Test
    void 경계_요청마다_새로_만든_값은_다르다() throws Exception {
        run(null);
        run(null);

        assertThat(seenInChain).hasSize(2).doesNotHaveDuplicates();
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_65자는_새_UUID로_바꾸고_길이만_경고한다(CapturedOutput output) throws Exception {
        String id = "zz9long" + "b".repeat(58);

        run(id);

        assertReplaced(output, 65);
        assertThat(output.getAll()).doesNotContain("zz9long");
    }

    @Test
    void 실패_줄바꿈이_들어오면_바꾸고_로그_줄이_깨지지_않는다(CapturedOutput output) throws Exception {
        run("rid-ok\nINJECTED-zz9 WARN fake line");

        assertReplaced(output, "rid-ok\nINJECTED-zz9 WARN fake line".length());
        assertThat(output.getAll()).doesNotContain("INJECTED-zz9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "rid zz9", "rid<zz9>", "rid/zz9", "rid:zz9", "rid\tzz9", "rid\rzz9", "조사zz9", "rid%0Azz9"})
    void 실패_허용_밖_문자는_바꾸고_원래_값은_안_찍는다(String bad, CapturedOutput output) throws Exception {
        run(bad);

        assertReplaced(output, bad.length());
        assertThat(output.getAll()).doesNotContain("zz9");
    }

    // --- 도우미 -------------------------------------------------------------

    private void run(String header) throws Exception {
        res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> seenInChain.add(MDC.get("request_id"));
        filter.doFilter(request(header), res, chain);
    }

    private static MockHttpServletRequest request(String header) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/cases");
        if (header != null) req.addHeader(RequestIdFilter.HEADER, header);
        return req;
    }

    private void assertReplaced(CapturedOutput output, int receivedLen) {
        String id = seenInChain.get(seenInChain.size() - 1);
        assertThat(id).matches(UUID_RE);
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
        List<String> lines = replacedLines(output);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("WARN").endsWith("request_id_replaced received_len=" + receivedLen);
    }

    private static List<String> replacedLines(CapturedOutput output) {
        return output.getAll().lines().filter(l -> l.contains("request_id_replaced")).toList();
    }
}
