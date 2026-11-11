package com.team.aml.user.domain;

import com.team.aml.shared.domain.BaseEntity;
import com.team.aml.shared.domain.InvalidRequestException;
import jakarta.persistence.*;

/**
 * users 테이블 (V003). 비밀번호 평문은 어디에도 없다 — 해시만.
 * 삭제 메서드 없음. 퇴사는 deactivate() (이력이 created_by 로 가리키므로 행을 지우지 않는다).
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final long SYSTEM_ID = 1L;

    /** 역할 변경은 조사역 ↔ 승인자만 — 400 details.role 메시지 (SCRUM-135 ②, 04 §7). */
    public static final String ROLE_SWAP_ONLY = "조사역(INVESTIGATOR)과 승인자(APPROVER) 사이만 바꿀 수 있습니다";

    @Column(name = "login_id", nullable = false, unique = true, length = 64)
    private String loginId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected User() {}

    public static User create(String loginId, String displayName, Role role, String passwordHash) {
        if (role == Role.SYSTEM) throw new IllegalArgumentException("SYSTEM 계정은 만들 수 없습니다");
        User u = new User();
        u.loginId = loginId;
        u.displayName = displayName;
        u.role = role;
        u.passwordHash = passwordHash;
        return u;
    }

    public void deactivate() { this.active = false; }
    public void activate() { this.active = true; }
    /**
     * 조사역 ↔ 승인자만. 관리자로·관리자에서·SYSTEM(어느 쪽이든)·같은 역할은 400 INVALID_REQUEST details.role.
     * 진행 중 담당 건수·관리자 본인 검사는 유스케이스(ChangeUserRole) 몫 — 엔티티는 DB 를 보지 않는다.
     */
    public void changeRole(Role to) {
        boolean swap = (role == Role.INVESTIGATOR && to == Role.APPROVER) || (role == Role.APPROVER && to == Role.INVESTIGATOR);
        if (!swap) {
            throw new InvalidRequestException("role", ROLE_SWAP_ONLY);
        }
        this.role = to;
    }
    public void changePasswordHash(String hash) { this.passwordHash = hash; }

    public boolean canLogin() { return active && role != Role.SYSTEM && passwordHash != null; }

    public String getLoginId() { return loginId; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
}
