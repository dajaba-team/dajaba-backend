package com.team.aml.shared.security;

/**
 * 비밀번호 비교에 쓴 users 값(LoginCandidate — 활성·역할·해시)을 AppLoginProvider 에서 JsonLoginFilter 로 넘기는 상자 (SCRUM-135 ②-b).
 * 인증 결과의 details 에 잠깐 실었다가 필터가 바로 꺼내 원래 details 로 되돌리고, 로그인 요청 attribute 에만 둔다
 * — 세션에 들어가는 인증 객체에는 해시가 남지 않는다. 성공 핸들러가 세션 등록 뒤 DB 를 다시 읽어 이 값과 비교한다.
 */
record AuthenticatedAs(Object details, LoginCandidate candidate) {

    @Override public String toString() { return "AuthenticatedAs[" + candidate + "]"; }   // 해시를 로그에 남기지 않는다
}