package com.team.aml.str.api;

import java.util.List;

/**
 * 참고 사례 찾기 요청 (계약서 S9). 형식만 받고 규칙(개수·값·중복·검색어 길이)은 SearchReferences 가 400 으로 —
 * 앞뒤 공백을 지운 뒤 길이를 세야 해서(@NfcMaxLength 는 원문을 셈).
 */
public record SearchReferencesRequest(List<String> typologies, String query) {}
