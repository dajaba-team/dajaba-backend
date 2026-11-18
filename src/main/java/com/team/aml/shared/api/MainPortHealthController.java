package com.team.aml.shared.api;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 8080 GET /actuator/health — 관리 포트를 8081 로 나누면서 프론트 연결 확인·docs/09·권한 표 25번을 지키려고 남긴 호환용 (SCRUM-271).
 * actuator 는 8081 로 옮겨 갔고, 여기서는 HealthEndpoint 의 상태만 {"status":…} 로 돌려준다(UP 200, 그 밖 503).
 * components·details 는 내보내지 않는다 — 전의 기본값(show-details: never)과 같은 모양.
 * Boot 의 health 그룹 additional-path 는 경로가 한 단계만 돼서(/health) 이 경로를 못 만든다.
 * 누구나 호출 — SecurityConfig 의 /actuator/health permitAll 그대로.
 */
@RestController
public class MainPortHealthController {

    private final HealthEndpoint health;

    public MainPortHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, String>> health() {
        Status status = health.health().getStatus();
        HttpStatus http = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(Map.of("status", status.getCode()));
    }
}
