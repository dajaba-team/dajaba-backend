package com.team.aml.shared.audit;

import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀원용 열람 기록 — 조회 유스케이스에서 한 줄로 부른다 (03_shared §9, 레퍼런스 §3.4. AOP 아님).
 *   accessLog.view(TargetType.CASE, caseId);   accessLog.strView(draftId);   accessLog.pdfDownload(draftId);
 * 사용자는 CurrentUser, IP 는 지금 요청(request.getRemoteAddr())에서 꺼낸다 — 호출부는 넘기지 않는다.
 * 별도 트랜잭션(REQUIRES_NEW)이라 readOnly 조회 안에서도 저장된다.
 * 저장이 실패하면 예외를 그대로 올린다 → 조회도 실패한다 (기록 없는 열람 금지, 11/2).
 */
@Component
public class AccessLogWriter {

    private final AccessLogRepository repository;
    private final CurrentUser currentUser;

    public AccessLogWriter(AccessLogRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    /** VIEW — 케이스·경보·사용자 상세. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void view(TargetType type, long targetId) {
        write(AccessLogAction.VIEW, type, targetId);
    }

    /** STR_VIEW — STR 초안 본문 열람. target_type STR_DRAFT. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void strView(long draftId) {
        write(AccessLogAction.STR_VIEW, TargetType.STR_DRAFT, draftId);
    }

    /** PDF_DOWNLOAD — 확정본 PDF. target_type STR_DRAFT. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pdfDownload(long draftId) {
        write(AccessLogAction.PDF_DOWNLOAD, TargetType.STR_DRAFT, draftId);
    }

    /** EXPORT — ADMIN 내보내기. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void export(TargetType type, long targetId) {
        write(AccessLogAction.EXPORT, type, targetId);
    }

    private void write(AccessLogAction action, TargetType type, long targetId) {
        Long userId = currentUser.get().map(AppUserDetails::userId).orElse(null);
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr() : null;
        repository.save(AccessLog.create(action, userId, null, type, targetId, ip));
    }
}
