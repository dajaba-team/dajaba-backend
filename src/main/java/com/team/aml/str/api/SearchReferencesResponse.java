package com.team.aml.str.api;

import com.team.aml.str.application.SearchReferences;

import java.util.List;

/**
 * 참고 사례 찾기 응답 (계약서 S9, 설계 문서 A 6.1). doc_id·chunk_id·retrieval_version 은 화면이 안 써서 주지 않는다.
 * 원문 본문은 제외하고 관련 구절 `excerpt` 만 제공한다(최대 300 UTF-16 코드 유닛, 없으면 null). 0건도 200 items [].
 */
public record SearchReferencesResponse(List<Item> items, boolean degraded) {

    public record Item(String docTitle, String publisher, Integer year, int page, double score,
                       List<String> typologies, String sourceType, String excerpt) {}

    static SearchReferencesResponse from(SearchReferences.Result r) {
        return new SearchReferencesResponse(r.results().stream()
                .map(x -> new Item(x.docTitle(), x.publisher(), x.year(), x.page(), x.score(), x.typologies(),
                        x.sourceType(), x.excerpt()))
                .toList(), r.degraded());
    }
}
