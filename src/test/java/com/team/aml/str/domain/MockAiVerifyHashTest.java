package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.ai.AiDtos.NarrativeSectionDto;
import com.team.aml.shared.ai.AiDtos.VerifyRequest;
import com.team.aml.shared.ai.MockAiClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 목업 검증 응답의 narrative_sha256 이 NarrativeHash 와 같은지 — 다르면 목업으로 다시 검증해도 GetStrDraft is_current 가
 * false 라 "이전 수정본을 검증한 결과"가 뜨고 ApprovalGate 가 승인 요청을 막는다.
 * MockAiClient 는 shared 라 NarrativeHash(str)를 못 써서 규칙을 따로 두므로, 같은 값인지를 str 쪽에서 묶는다.
 */
class MockAiVerifyHashTest {

    private final MockAiClient client = new MockAiClient(new ObjectMapper());

    private static List<NarrativeSection> body(List<StrSection> set) {
        List<NarrativeSection> out = new ArrayList<>();
        for (StrSection s : set) {
            // NFC 전 한글(자모 분리)·줄바꿈·탭·제어문자·따옴표·역슬래시까지 — 해시 규칙의 까다로운 칸
            String text = s.name() + " 본문\n두 줄\t탭 \u001f \"따옴표\" \\ 가 USD 309,200";
            out.add(new NarrativeSection(s, text, List.of("subject"), List.of()));
        }
        return out;
    }

    private String mockHash(List<NarrativeSection> body) {
        List<NarrativeSectionDto> dto = body.stream()
                .map(n -> new NarrativeSectionDto(n.section().name(), n.text(), n.cites(), n.evidenceIds()))
                .toList();
        return client.verify("DRF-000001", new VerifyRequest("g-1", 3, dto)).narrativeSha256();
    }

    @Test
    void 목업_검증_해시는_NarrativeHash와_같다_V2() {
        List<NarrativeSection> body = body(StrSection.V2);
        assertThat(mockHash(body)).isEqualTo(NarrativeHash.of(body));
    }

    @Test
    void 목업_검증_해시는_NarrativeHash와_같다_V1() {
        List<NarrativeSection> body = body(StrSection.V1);
        assertThat(mockHash(body)).isEqualTo(NarrativeHash.of(body));
    }

    @Test
    void 요청_단락_순서가_섞여도_같은_해시() {
        List<NarrativeSection> body = body(StrSection.V2);
        List<NarrativeSection> shuffled = new ArrayList<>(body);
        Collections.reverse(shuffled);
        assertThat(mockHash(shuffled)).isEqualTo(NarrativeHash.of(body));
    }

    @Test
    void 본문이_바뀌면_해시도_바뀐다() {
        List<NarrativeSection> body = body(StrSection.V2);
        List<NarrativeSection> edited = new ArrayList<>(body);
        NarrativeSection first = edited.get(0);
        edited.set(0, new NarrativeSection(first.section(), first.text() + " 수정", first.cites(), first.evidenceIds()));
        assertThat(mockHash(edited)).isNotEqualTo(mockHash(body));
    }
}
