package com.team.aml.str.domain;
import com.team.aml.shared.domain.DomainException;

/** 사용 중(ACTIVE)이 아닌 초안을 고치려 할 때. 잠김·대체됨 모두. */
public class DraftLockedException extends DomainException{
    public DraftLockedException(StrDraftStatus status){
        super("사용 중이 아닌 초안을 고칠 수 없습니다. 현재 상태 : "+status);
    }
}
