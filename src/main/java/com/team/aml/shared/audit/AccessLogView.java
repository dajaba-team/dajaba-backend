package com.team.aml.shared.audit;

import java.time.Instant;

/**
 * 접근 기록 한 행 (SCRUM-168, 계약서 5.10). ip·user_id·DB 숫자 target_id 는 처음부터 없다.
 * loginId·displayName = 행위자(LOGIN_FAIL 은 null), targetId = 화면 번호(CASE-…·DRF-…·ALT-…·사용자 login_id).
 * created_at 은 시스템 시각이라 UTC Z·초 단위 (미결 27).
 */
public record AccessLogView(long id, String loginId, String displayName, String loginIdAttempted, String action,
                            String targetType, String targetId, Instant createdAt) {
}
