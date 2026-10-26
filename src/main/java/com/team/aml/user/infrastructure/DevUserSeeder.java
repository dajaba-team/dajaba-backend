package com.team.aml.user.infrastructure;

import com.team.aml.shared.config.AmlProperties;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 개발·시연용 사용자 4명 (레퍼런스 9.5). local 프로파일(aml.dev-users.enabled=true)에서만 빈이 등록된다.
 * 비밀번호는 환경변수 DEV_USER_PASSWORD — SQL 파일에 해시를 박으면 git 에 남아서 여기서 만든다. 이미 있으면 건너뛴다.
 *
 *  admin  ADMIN         배치 버튼 4개, 담당자 교체, 계정 발급
 *  inv01  INVESTIGATOR  케이스 배정 → 에스컬레이션 → 초안 → 수정 → 검증 → 승인 요청
 *  apr01  APPROVER      승인 · 반려
 *  inv02  INVESTIGATOR  "남의 케이스 → 403", "동시 수정 → 409" 시연용 (10/22 추가)
 */
@Component
@ConditionalOnProperty(prefix = "aml.dev-users", name = "enabled", havingValue = "true")
public class DevUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private record Seed(String loginId, String displayName, Role role) {}

    private static final List<Seed> SEEDS = List.of(
            new Seed("admin", "관리자", Role.ADMIN),
            new Seed("inv01", "조사역 1", Role.INVESTIGATOR),
            new Seed("apr01", "승인자 1", Role.APPROVER),
            new Seed("inv02", "조사역 2", Role.INVESTIGATOR));

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AmlProperties props;

    public DevUserSeeder(UserRepository users, PasswordEncoder encoder, AmlProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(String... args) {
        String password = props.devUsers() == null ? null : props.devUsers().password();
        if (password == null || password.isBlank()) {
            log.warn("DEV_USER_PASSWORD 가 비어 있어 개발용 사용자를 만들지 않습니다 (.env 확인, 값은 노션)");
            return;
        }
        String hash = encoder.encode(password);
        int created = 0;
        for (Seed s : SEEDS) {
            if (users.existsByLoginId(s.loginId())) continue;
            users.save(User.create(s.loginId(), s.displayName(), s.role(), hash));
            created++;
        }
        log.info("DevUserSeeder: {}명 생성, {}명 이미 있음 (admin, inv01, apr01, inv02)", created, SEEDS.size() - created);
    }
}
