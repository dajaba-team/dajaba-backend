package com.team.aml.shared.batch;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실행 중 배치의 진행 단계 (SCRUM-349) — 적재(파일 단계)·룰 스캔(룰 단위). 상태 조회(B5)·목록이 RUNNING 행에만 붙여 준다.
 * <b>앱 메모리에만</b> 둔다: 룰 스캔은 한 트랜잭션이라 DB 에 쓰면 커밋 전엔 안 보이고, 따로 커밋하면 같은 행을 두 트랜잭션이 고친다.
 * 재시작하면 사라지지만 그때 RUNNING 행은 기동 정리로 FAILED 가 된다. 단일 서버 전제(배치 실행과 조회가 같은 앱).
 * 단계 이름(current)은 코드 값 — 룰 ID 또는 적재 단계(PATTERNS·ACCOUNTS·TRANSACTIONS·VERIFY). 화면이 글자로 바꾼다.
 */
@Component
public class BatchProgress {

    /**
     * @param done    끝난 단계 수
     * @param total   전체 단계 수
     * @param current 지금 도는 단계(코드 값)
     */
    public record Step(int done, int total, String current) {
    }

    private final Map<Long, Step> steps = new ConcurrentHashMap<>();

    /** current 단계를 시작했다 — 앞의 done 개는 끝남. */
    public void at(long batchRunId, int done, int total, String current) {
        steps.put(batchRunId, new Step(done, total, current));
    }

    /** 끝나면(성공·실패 모두) 지운다. */
    public void clear(long batchRunId) {
        steps.remove(batchRunId);
    }

    public Optional<Step> of(long batchRunId) {
        return Optional.ofNullable(steps.get(batchRunId));
    }
}
