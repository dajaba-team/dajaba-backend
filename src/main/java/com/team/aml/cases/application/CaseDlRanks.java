package com.team.aml.cases.application;

import com.team.aml.detection.application.DlRankingResult;
import com.team.aml.detection.application.DlRankingResult.Available;
import com.team.aml.detection.application.DlRankingResult.DlRank;
import com.team.aml.detection.application.DlRankingResult.Reason;
import com.team.aml.detection.application.DlRankingResult.Unavailable;
import com.team.aml.detection.application.GetDlRanking;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 케이스 목록·상세에 ML+DL 조사 순위를 붙인다 (SCRUM-420). detection {@link GetDlRanking} 을 <b>배치 날짜마다 한 번</b> 부른다.
 * <ul>
 *   <li><b>트랜잭션이 없다</b> — 컨트롤러가 조회 유스케이스(ListCases·GetCaseDetail)를 끝낸 뒤 부른다.
 *       조회 트랜잭션 안에서 부르면 AI 를 기다리는 동안(최대 10초) DB 커넥션을 잡고 있다(source_detection.md)</li>
 *   <li>Unavailable 이면 그날 경보 전부 UNAVAILABLE — 순위 일부만 쓰지 않는다</li>
 *   <li>DB 예외는 그대로 던진다(GetDlRanking 과 같음) — 순위가 없는 것과 DB 장애를 같은 "조회 불가"로 숨기지 않게</li>
 *   <li>저장·캐시 없음. 목록 한 페이지에 날짜가 여럿이면 AI 를 날짜 수만큼 차례로 부른다</li>
 * </ul>
 */
@Service
public class CaseDlRanks {

    /** 순위를 붙일 경보 — 경보 번호(ALT-YYYYMMDD-NNNNNN)와 그 배치 날짜. */
    public record AlertRef(String alertNo, LocalDate batchDate) {
    }

    private final GetDlRanking getDlRanking;

    public CaseDlRanks(GetDlRanking getDlRanking) {
        this.getDlRanking = getDlRanking;
    }

    /** 경보 하나 — 상세. */
    public CaseDlRank forAlert(String alertNo, LocalDate batchDate) {
        return forAlerts(List.of(new AlertRef(alertNo, batchDate))).get(alertNo);
    }

    /**
     * 경보 여럿 — 목록 한 페이지. 번호·날짜가 없는 경보는 건너뛴다(결과에 없음 → 화면은 "순위 조회 불가").
     *
     * @return 경보 번호 → 순위
     */
    public Map<String, CaseDlRank> forAlerts(Collection<AlertRef> alerts) {
        Map<LocalDate, List<String>> byDate = new LinkedHashMap<>();
        for (AlertRef a : alerts) {
            if (a != null && a.alertNo() != null && a.batchDate() != null) {
                byDate.computeIfAbsent(a.batchDate(), d -> new ArrayList<>()).add(a.alertNo());
            }
        }
        Map<String, CaseDlRank> ranks = new HashMap<>();
        byDate.forEach((date, alertNos) -> {
            DlRankingResult result = getDlRanking.execute(date);
            for (String alertNo : alertNos) {
                ranks.put(alertNo, rankOf(result, alertNo));
            }
        });
        return ranks;
    }

    private static CaseDlRank rankOf(DlRankingResult result, String alertNo) {
        if (result instanceof Unavailable u) {
            return CaseDlRank.unavailable(u.reason().name());
        }
        DlRank r = ((Available) Objects.requireNonNull(result, "result")).ranks().get(alertNo);
        if (r == null) {
            // Available 은 그날 경보 집합이 응답과 같을 때만이라 생기지 않는다. 생기면 엉뚱한 순위 대신 조회 불가
            return CaseDlRank.unavailable(Reason.ALERT_SET_MISMATCH.name());
        }
        return CaseDlRank.available(r.mlRank(), r.fusedRank(), r.fallbackReason(), r.dlScore());
    }
}
