package com.team.aml.shared.config;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 관리 포트(8081) 듣는 주소 시작 검사 (03_shared §13, SCRUM-271 후속).
 *
 * 환경 변수 MANAGEMENT_SERVER_ADDRESS 는 yml 의 ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1} 기본값을 거치지 않고
 * management.server.address 를 바로 덮는다. 빈 값·공백이면 주소가 null 이 돼 Tomcat 이 모든 주소에 바인딩하고,
 * 인증 없는 /actuator/prometheus 가 밖에서 보인다 — yml 로는 못 막아서 앱이 뜰 때 최종 값을 본다.
 * 조용히 127.0.0.1 로 바꾸지 않고 기동을 멈춘다(잘못 넣은 설정을 사람이 보게).
 * 관리 포트가 앱 포트와 같으면 Boot 가 이 주소를 쓰지 않으니 검사하지 않는다.
 * 아무 빈도 이 빈을 참조하지 않아서 spring.main.lazy-initialization=true 면 안 만들어진다 → @Lazy(false) 로 항상 만든다.
 */
@Component
@Lazy(false)
public class ManagementAddressCheck {

    static final String PROPERTY = "management.server.address";

    public ManagementAddressCheck(Environment env) {
        if (ManagementPortType.get(env) != ManagementPortType.DIFFERENT) {
            return;
        }
        String address = env.getProperty(PROPERTY);
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("MANAGEMENT_SERVER_ADDRESS 비어 있음 — 관리 포트(지표)가 모든 주소에 열립니다. "
                    + "변수를 지우거나(기본 127.0.0.1) 127.0.0.1 또는 0.0.0.0(compose 컨테이너) 으로 넣으세요");
        }
    }
}
