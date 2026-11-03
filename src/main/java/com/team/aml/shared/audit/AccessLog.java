package com.team.aml.shared.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

/**
 * 열람·로그인 기록 한 행 (레퍼런스 §3.4, V004_3). created_at 만, setter 없음, 추가만 — AiCallLog 와 같은 모양.
 * 비밀번호는 어떤 칸에도 들어가지 않는다.
 */
@Entity
@Table(name = "access_logs")
public class AccessLog {

    private static final int MAX_LOGIN_ID_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "login_id_attempted", length = MAX_LOGIN_ID_LENGTH, updatable = false)
    private String loginIdAttempted;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 16, updatable = false)
    private TargetType targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16, updatable = false)
    private AccessLogAction action;

    @Column(name = "ip", columnDefinition = "inet", updatable = false)
    @ColumnTransformer(write = "?::inet")
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccessLog() {}

    /** loginIdAttempted 는 64자 넘으면 앞 64자만. 로그인 실패가 아니면 null. shared/audit 의 Writer 만 부른다(SCRUM-93). */
    static AccessLog create(AccessLogAction action, Long userId, String loginIdAttempted,
                                   TargetType targetType, Long targetId, String ip) {
        AccessLog log = new AccessLog();
        log.action = action;
        log.userId = userId;
        log.loginIdAttempted = loginIdAttempted == null ? null
                : loginIdAttempted.substring(0, loginIdAttempted.offsetByCodePoints(0,
                        Math.min(loginIdAttempted.codePointCount(0, loginIdAttempted.length()), MAX_LOGIN_ID_LENGTH)));
        log.targetType = targetType;
        log.targetId = targetId;
        log.ip = validIpOrNull(ip);
        log.createdAt = Instant.now();
        return log;
    }

    private static String validIpOrNull(String ip) {
        if (ip == null) return null;
        // IPv4 네 옥텟 또는 ':'가 있는 IPv6 리터럴만 파싱해 DNS 조회를 막는다.
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                && !(ip.contains(":") && ip.matches("[0-9a-fA-F:.]+"))) return null;
        try {
            return InetAddress.getByName(ip).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getLoginIdAttempted() { return loginIdAttempted; }
    public TargetType getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public AccessLogAction getAction() { return action; }
    public String getIp() { return ip; }
    public Instant getCreatedAt() { return createdAt; }
}
