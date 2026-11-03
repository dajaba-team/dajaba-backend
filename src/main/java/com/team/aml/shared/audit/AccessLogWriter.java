package com.team.aml.shared.audit;

import com.team.aml.shared.security.CurrentUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 팀원용 열람 기록 — 조회 유스케이스에서 한 줄로 부른다 (03_shared §9, 레퍼런스 §3.4. AOP 아님).
 *   accessLog.view(TargetType.CASE, caseId);   accessLog.strView(draftId);   accessLog.pdfDownload(draftId);
 * 사용자는 CurrentUser(없으면 실패), IP 는 지금 요청(request.getRemoteAddr())에서 꺼낸다 — 호출부는 넘기지 않는다.
 * 바깥 조회 트랜잭션에 참여한다(MANDATORY, 같은 커넥션) — 부르는 유스케이스는 @Transactional(readOnly 아님).
 * 트랜잭션 밖이면 IllegalTransactionStateException, readOnly 안이면 IllegalStateException 으로 바로 실패한다.
 * 기록과 조회는 같이 성공·같이 실패한다. 저장이 실패하면 예외를 그대로 올린다 (기록 없는 열람 금지, 11/2 · SCRUM-93).
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
    @Transactional(propagation = Propagation.MANDATORY)
    public void view(TargetType type, long targetId) {
        write(AccessLogAction.VIEW, type, targetId);
    }

    /** STR_VIEW — STR 초안 본문 열람(제한 응답 포함, 미결 30). target_type STR_DRAFT. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void strView(long draftId) {
        write(AccessLogAction.STR_VIEW, TargetType.STR_DRAFT, draftId);
    }

    /** PDF_DOWNLOAD — 확정본 PDF. target_type STR_DRAFT. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pdfDownload(long draftId) {
        write(AccessLogAction.PDF_DOWNLOAD, TargetType.STR_DRAFT, draftId);
    }

    /** EXPORT — ADMIN 내보내기. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void export(TargetType type, long targetId) {
        write(AccessLogAction.EXPORT, type, targetId);
    }

    private void write(AccessLogAction action, TargetType type, long targetId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("열람 기록은 readOnly가 아닌 조회 트랜잭션 안에서 불러야 해요 — 03_shared §9");
        }
        long userId = currentUser.require().userId();
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr() : null;
        repository.save(AccessLog.create(action, userId, null, type, targetId, ip));
    }
}