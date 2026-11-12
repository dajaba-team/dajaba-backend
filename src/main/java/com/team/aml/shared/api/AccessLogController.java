package com.team.aml.shared.api;

import com.team.aml.shared.audit.AccessLogQuery;
import com.team.aml.shared.audit.AccessLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 접근 기록 조회 (SCRUM-168, 권한 표 24번). ADMIN 만 — SecurityConfig 의 /api/v1/admin/** 규칙. 이 목록은 접근 기록을 남기지 않는다. */
@Tag(name = "access-logs")
@RestController
@RequestMapping("/api/v1/admin/access-logs")
public class AccessLogController {

    private final AccessLogQuery query;

    public AccessLogController(AccessLogQuery query) {
        this.query = query;
    }

    @Operation(summary = "접근 기록 목록 (메타만)",
            description = "필터는 전부 선택·AND. login_id = 행위자(LOGIN_FAIL 은 안 잡힘). "
                    + "target_id 는 화면 번호이고 target_type 과 같이. from·to 는 UTC Z(from 포함·to 제외). "
                    + "정렬 created_at 만, 기본 created_at,desc. size 최대 100. ip 는 응답에 없음")
    @GetMapping
    public PageResponse<AccessLogView> list(@Valid @ParameterObject @ModelAttribute AccessLogListQuery q) {
        return PageResponse.from(query.search(q.toFilter(), q.pageOrDefault(), q.sizeOrDefault(), q.sortOrDefault()));
    }
}
