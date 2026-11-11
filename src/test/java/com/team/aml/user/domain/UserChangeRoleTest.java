package com.team.aml.user.domain;

import com.team.aml.shared.domain.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** User.changeRole — 조사역 ↔ 승인자만 (SCRUM-135 ②). */
class UserChangeRoleTest {

    @Test
    void 정상_조사역과_승인자는_서로_바뀐다() {
        User u = User.create("u1", "이름", Role.INVESTIGATOR, "hash");
        u.changeRole(Role.APPROVER);
        assertThat(u.getRole()).isEqualTo(Role.APPROVER);
        u.changeRole(Role.INVESTIGATOR);
        assertThat(u.getRole()).isEqualTo(Role.INVESTIGATOR);
    }

    @Test
    void 경계_같은_역할은_400_details_role이고_그대로() {
        User u = User.create("u2", "이름", Role.APPROVER, "hash");
        assertInvalid(u, Role.APPROVER);
        assertThat(u.getRole()).isEqualTo(Role.APPROVER);
    }

    @Test
    void 실패_관리자로_관리자에서_SYSTEM으로_SYSTEM에서는_400_details_role() {
        assertInvalid(User.create("u3", "이름", Role.INVESTIGATOR, "hash"), Role.ADMIN);
        assertInvalid(User.create("u4", "이름", Role.INVESTIGATOR, "hash"), Role.SYSTEM);
        assertInvalid(User.create("u5", "이름", Role.ADMIN, "hash"), Role.APPROVER);
        User system = User.create("u6", "이름", Role.APPROVER, null);
        ReflectionTestUtils.setField(system, "role", Role.SYSTEM);   // SYSTEM 은 create 로 못 만든다(V003 시드만)
        assertInvalid(system, Role.INVESTIGATOR);
        assertThat(system.getRole()).isEqualTo(Role.SYSTEM);
    }

    private static void assertInvalid(User u, Role to) {
        assertThatThrownBy(() -> u.changeRole(to))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).details()).containsOnlyKeys("role"));
    }
}
