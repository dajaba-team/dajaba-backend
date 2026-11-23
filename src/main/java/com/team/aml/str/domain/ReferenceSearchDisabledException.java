package com.team.aml.str.domain;

import com.team.aml.shared.domain.ForbiddenException;

/**
 * 403 FORBIDDEN — 참고 사례 검색이 꺼져 있음(aml.ai.reference-search-enabled=false). 설계 문서 A 13-4: 새 코드 없이 FORBIDDEN.
 * 하위 클래스 이름이 코드가 되는 규칙(ForbiddenException)을 이 한 곳만 덮어쓴다 — 화면은 버튼을 숨기므로 보통 보이지 않는다.
 */
public class ReferenceSearchDisabledException extends ForbiddenException {

    public ReferenceSearchDisabledException() {
        super("참고 사례 검색이 꺼져 있습니다");
    }

    @Override
    public String code() {
        return "FORBIDDEN";
    }
}
