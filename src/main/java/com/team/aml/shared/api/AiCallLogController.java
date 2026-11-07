package com.team.aml.shared.api;

import com.team.aml.shared.ai.AiCallLogQuery;
import com.team.aml.shared.ai.AiCallLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 호출 기록 조회 (SCRUM-134, 권한 표 24번). ADMIN 만 — SecurityConfig 의 /api/v1/admin/** 규칙. 접근 기록은 남기지 않는다. */
@Tag(name = "ai-calls")
@RestController
@RequestMapping("/api/v1/admin/ai-calls")
public class AiCallLogController {

    private final AiCallLogQuery query;

    public AiCallLogController(AiCallLogQuery query) {
        this.query = query;
    }

    @Operation(summary = "AI 호출 기록 목록 (메타만)",
            description = "필터는 전부 선택·AND. outcome SUCCESS = error_code 없음, FAILURE = 있음. "
                    + "from·to 는 UTC Z(from 포함·to 제외). 정렬 created_at 만, 기본 created_at,desc. size 최대 100")
    @GetMapping
    public PageResponse<AiCallLogView> list(@Valid @ParameterObject @ModelAttribute AiCallLogListQuery q) {
        return PageResponse.from(query.search(q.toFilter(), q.pageOrDefault(), q.sizeOrDefault(), q.sortOrDefault()));
    }
}
