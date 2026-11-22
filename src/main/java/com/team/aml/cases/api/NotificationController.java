package com.team.aml.cases.api;

import com.team.aml.cases.application.ListMyNotifications;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 업무 알림 (SCRUM-355, 노션 「비동기 작업 알림 설계 문서」 6.5). 로그인한 세 역할 모두 — SecurityConfig 의 /api/** authenticated.
 * 받는 사람은 세션 사용자뿐이라 403 이 없다.
 */
@Tag(name = "notifications")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final ListMyNotifications listMyNotifications;

    public NotificationController(ListMyNotifications listMyNotifications) {
        this.listMyNotifications = listMyNotifications;
    }

    @Operation(summary = "내 업무 알림",
            description = "배정·담당 변경·승인 요청·승인·반려·모의 제출 중 나에게 온 것, 최신 먼저. 자기가 한 일은 빠짐, 관리자는 빈 목록. "
                    + "since(UTC Z, 없으면 7일 전, 30일보다 오래되면 30일 전) · size 1~50(기본 20). "
                    + "total 은 since 뒤 전체 건수(99 에서 멈춤), server_time 은 다음 since 로 쓰는 값")
    @GetMapping
    public ListMyNotifications.Result list(@Valid @ParameterObject @ModelAttribute NotificationQuery query) {
        return listMyNotifications.execute(query.sinceOrNull(), query.sizeOrDefault());
    }
}
