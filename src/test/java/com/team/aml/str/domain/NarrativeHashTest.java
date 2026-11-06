package com.team.aml.str.domain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 본문 해시 단위 테스트 (스프링 없이 돈다).
 * 기준: 10/27 안건 23 해시 규칙, 채영 str 작업 가이드 W2 "NarrativeHashTest".
 */
public class NarrativeHashTest {

    private static List<NarrativeSection> body() {
        return StrSection.V1.stream()
                .map(s -> new NarrativeSection(
                        s,
                        "본문 " + s,
                        List.of(5_120_000L + s.ordinal())))
                .toList();
    }

    /** 본문에서 한 단락의 문장만 바꾼 복사본. 나머지는 그대로. */
    private static List<NarrativeSection> withText(List<NarrativeSection> body, StrSection target, String text) {
        return body.stream()
                .map(n -> n.section() == target
                        ? new NarrativeSection(n.section(), text, n.evidenceIds())
                        : n)
                .toList();
    }

    /** 본문에서 한 단락의 근거 번호만 바꾼 복사본. 문장은 그대로. */
    private static List<NarrativeSection> withEvidence(List<NarrativeSection> body, StrSection target, List<Long> evidenceIds) {
        return body.stream()
                .map(n -> n.section() == target
                        ? new NarrativeSection(n.section(), n.text(), evidenceIds)
                        : n)
                .toList();
    }

    // 1
    @Test
    void 같은_본문이면_같은_해시이고_글자_하나만_바꿔도_다른_해시다() {
        String first = NarrativeHash.of(body());
        String again = NarrativeHash.of(body());
        String oneCharChanged = NarrativeHash.of(withText(body(), StrSection.HOW, "본문 HOW!"));

        assertThat(first)
                .isEqualTo(again)
                .matches("[0-9a-f]{64}");          // SHA-256 = 16진수 소문자 64자리
        assertThat(oneCharChanged).isNotEqualTo(first);
    }

    // 2
    @Test
    void 근거_번호만_다르면_같은_해시다() {
        List<NarrativeSection> original = body();
        List<NarrativeSection> refsChanged = withEvidence(original, StrSection.HOW, List.of(9_999_001L, 9_999_002L));

        assertThat(refsChanged).isNotEqualTo(original);                       // 본문 객체는 정말 다르고
        assertThat(NarrativeHash.of(refsChanged)).isEqualTo(NarrativeHash.of(original));  // 해시는 같다
    }

    // 2-2: cites(근거 facts 키)도 해시에 안 들어간다 — 규약 v0.4, cites 칸 추가 뒤에도 예전 검증 해시가 그대로
    @Test
    void cites만_다르면_같은_해시다() {
        List<NarrativeSection> original = body();
        List<NarrativeSection> citesChanged = original.stream()
                .map(n -> n.section() == StrSection.HOW
                        ? new NarrativeSection(n.section(), n.text(), List.of("period", "inbound.count"), n.evidenceIds())
                        : n)
                .toList();

        assertThat(citesChanged).isNotEqualTo(original);
        assertThat(NarrativeHash.of(citesChanged)).isEqualTo(NarrativeHash.of(original));
    }

    // 3
    @Test
    void 단락_순서가_섞여_와도_정해진_순서로_계산해서_같은_해시다() {
        List<NarrativeSection> inOrder = body();
        List<NarrativeSection> reversed = new ArrayList<>(inOrder);
        Collections.reverse(reversed);                          // CONCLUSION, …, SUMMARY

        assertThat(reversed).isNotEqualTo(inOrder);              // 순서가 정말 다르고
        assertThat(NarrativeHash.of(reversed)).isEqualTo(NarrativeHash.of(inOrder));   // 해시는 같다
    }

    // 4
    @Test
    void 조합형과_완성형_한글은_같은_해시다() {
        String composed = "한글 본문";                                              // 완성형(NFC)
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);    // 조합형(NFD), 눈으로는 같음

        assertThat(decomposed).isNotEqualTo(composed);                  // 컴퓨터에겐 다른 문자열이고
        assertThat(decomposed).hasSize(13);                              // 글자 수도 5 → 13

        String nfcHash = NarrativeHash.of(withText(body(), StrSection.WHO, composed));
        String nfdHash = NarrativeHash.of(withText(body(), StrSection.WHO, decomposed));
        assertThat(nfdHash).isEqualTo(nfcHash);                          // 해시는 같아야 한다
    }

    // 5: Java와 Python(AI)이 같은 값을 내는지 — 안건 23 테스트 벡터
    //    정답 파일: contracts/examples/narrative_hash.json (AI 리포와 같은 파일, Python이 만든 값)
    @Test
    void 테스트_벡터_본문은_AI가_계산한_해시와_같다() throws Exception {
        JsonNode vector = new ObjectMapper().readTree(
                Files.readString(Path.of("contracts/examples/narrative_hash.json")));

        List<NarrativeSection> narrative = new ArrayList<>();
        for (JsonNode s : vector.get("narrative")) {
            List<Long> evidenceIds = new ArrayList<>();
            s.get("evidence_ids").forEach(id -> evidenceIds.add(id.asLong()));
            narrative.add(new NarrativeSection(
                    StrSection.valueOf(s.get("section").asText()),
                    s.get("text").asText(),
                    evidenceIds));
        }

        assertThat(NarrativeHash.of(narrative)).isEqualTo(vector.get("sha256").asText());
    }

    // 5-2: 제어문자는 Python처럼 소문자 \u001f 로 이스케이프해야 같은 해시가 나온다
    //      정답값: Python json.dumps(ensure_ascii=False, separators=(",",":")) 결과의 SHA-256
    //      (body()에서 WHO 문장만 아래 값으로 바꾼 본문)
    @Test
    void 제어문자가_든_본문도_Python과_같은_해시다() {
        List<NarrativeSection> narrative = withText(body(), StrSection.WHO, "세로탭\u000b끝\u001f널\u0000");

        assertThat(NarrativeHash.of(narrative))
                .isEqualTo("4d84c49ca24bf9d96918f31de8f10a4278e778bfc05171358e61167b4326ab7f");
    }

    // 6
    @Test
    void 단락이_6개_각각_한_번씩이_아니면_해시하지_않고_거부한다() {
        List<NarrativeSection> full = body();

        List<NarrativeSection> five = full.subList(0, 5);                       // CONCLUSION 빠짐

        List<NarrativeSection> seven = new ArrayList<>(full);
        seven.add(full.get(0));                                                 // SUMMARY 두 번

        List<NarrativeSection> sixButWrong = new ArrayList<>(full);
        sixButWrong.set(1, full.get(0));                                        // WHO 빠지고 SUMMARY 두 번
        assertThat(sixButWrong).hasSize(6);                                     // 개수만 보면 맞아 보인다

        for (List<NarrativeSection> wrong : List.of(five, seven, sixButWrong)) {
            assertThatThrownBy(() -> NarrativeHash.of(wrong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> full.forEach(n ->
                            assertThat(e.getMessage()).doesNotContain(n.text())));   // 본문 문장은 메시지에 없다
        }

        assertThatThrownBy(() -> NarrativeHash.of(sixButWrong))
                .hasMessageContaining("WHO")                                    // 무엇이 빠졌는지는 알려 준다
                .hasMessageContaining("SUMMARY");                               // 무엇이 겹쳤는지도
    }

    /** 테스트 벡터 파일을 본문으로 읽는다. */
    private static List<NarrativeSection> readVector(JsonNode vector) {
        List<NarrativeSection> narrative = new ArrayList<>();
        for (JsonNode s : vector.get("narrative")) {
            List<Long> evidenceIds = new ArrayList<>();
            s.get("evidence_ids").forEach(id -> evidenceIds.add(id.asLong()));
            narrative.add(new NarrativeSection(
                    StrSection.valueOf(s.get("section").asText()), s.get("text").asText(), evidenceIds));
        }
        return narrative;
    }

    // 7: 서술 7항목(V2)도 Java와 Python이 같은 값 — 정답 파일 contracts/examples/narrative_hash_v2.json
    //    (AI 리포와 같은 파일, SCRUM-47). 파일 안 단락 순서는 섞여 있고, 앞뒤 공백·줄바꿈이 든 문장도 있다
    @Test
    void 서술_7항목_테스트_벡터도_AI가_계산한_해시와_같다() throws Exception {
        JsonNode vector = new ObjectMapper().readTree(
                Files.readString(Path.of("contracts/examples/narrative_hash_v2.json")));

        assertThat(NarrativeHash.of(readVector(vector))).isEqualTo(vector.get("sha256").asText());
    }

    // 8: 6섹션 세트와 7항목 세트가 섞이면 해시하지 않는다
    @Test
    void 섹션_세트가_섞이면_해시하지_않고_거부한다() {
        List<NarrativeSection> mixed = new ArrayList<>(body());
        mixed.set(0, new NarrativeSection(StrSection.SUBJECT, "본문 SUBJECT", List.of()));   // SUMMARY 대신 SUBJECT

        assertThatThrownBy(() -> NarrativeHash.of(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUBJECT")
                .hasMessageContaining("SUMMARY");
    }
}
