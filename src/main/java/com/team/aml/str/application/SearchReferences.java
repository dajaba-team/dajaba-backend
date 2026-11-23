package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos.ReferenceResult;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchRequest;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchResponse;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.InvalidRequestException;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.ReferenceSearchDisabledException;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.user.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 참고 사례 찾기 (계약서 S9, 설계 문서 「참고 사례 검색 · 승인 STR 색인」 A 6.1, SCRUM-354). 조회 전용 — 저장·열람 기록 없음.
 * <ol>
 *   <li>설정 aml.ai.reference-search-enabled 가 꺼져 있으면 403 FORBIDDEN (AI 안 부름, A 13-4)</li>
 *   <li>짧은 읽기 트랜잭션: 초안(404 DRAFT_NOT_FOUND) → 케이스 → 본문 열람 권한(GetStrDraft 와 같은 판단 — 담당 조사역·승인자,
 *       아니면 403 NOT_ASSIGNEE). 초안 상태로는 막지 않는다(잠긴·대체된 초안도 찾아볼 수 있음)</li>
 *   <li>입력: 유형 1~3개·5종 안·중복 없음, 검색어는 앞뒤 공백을 지우고 빈 값이면 null, NFC 뒤 200자 이하. 틀리면 400 (AI 안 부름)</li>
 *   <li>트랜잭션 밖에서 AiClient.searchReferences — 전체 8초·재시도·응답 확인은 AiClient. top_k 는 5, 출처는 PUBLIC 만</li>
 * </ol>
 * 검색어는 로그에 남기지 않는다 — 유형·글자 수·상태·결과 수만 (A 6.1 기록).
 */
@Service
public class SearchReferences {

    private static final Logger log = LoggerFactory.getLogger(SearchReferences.class);

    /** 화면에서 받지 않고 백엔드가 정한다 (A 6.1). */
    static final int TOP_K = 5;
    /** APPROVED_STR 은 아직 검색에 안 붙는다(SCRUM-310) — 공개 자료만. */
    static final List<String> SOURCE_TYPES = List.of("PUBLIC");
    static final Set<String> TYPOLOGIES = Set.of("FAN_IN", "FAN_OUT", "CYCLE", "SCATTER_GATHER", "GATHER_SCATTER");
    static final int MAX_TYPOLOGIES = 3;
    static final int QUERY_MAX = 200;

    /** results 는 AI 결과 그대로(본문 칸 없음) — 화면 칸 고르기는 api 응답 record 가. */
    public record Result(List<ReferenceResult> results, boolean degraded) {}

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final CurrentUser currentUser;
    private final AiClient aiClient;
    private final AmlProperties properties;
    private final TransactionTemplate readTx;

    public SearchReferences(StrDraftRepository drafts, CaseRepository cases, CurrentUser currentUser, AiClient aiClient,
                            AmlProperties properties, PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.cases = cases;
        this.currentUser = currentUser;
        this.aiClient = aiClient;
        this.properties = properties;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public Result execute(String draftNo, List<String> typologies, String query) {
        if (!properties.ai().referenceSearchEnabled()) {
            throw new ReferenceSearchDisabledException();
        }
        AppUserDetails me = currentUser.require();
        long draftId = DraftNumbers.require(draftNo);
        readTx.executeWithoutResult(s -> requireReader(draftNo, draftId, me));
        List<String> types = typologies(typologies);
        String q = query(query);
        ReferenceSearchResponse r = aiClient.searchReferences(draftNo,
                new ReferenceSearchRequest(types, q, TOP_K, SOURCE_TYPES));
        log.info("참고 사례 찾기 draft_no={} typologies={} query_length={} status={} results={}",
                draftNo, types, q == null ? 0 : q.length(), r.status(), r.results().size());
        return new Result(r.results(), r.searchMeta().degraded());
    }

    private void requireReader(String draftNo, long draftId, AppUserDetails me) {
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        if (!GetStrDraft.canReadBody(Role.valueOf(me.role()), c, me.userId())) {
            throw new NotAssigneeException(c.getCaseNo());
        }
    }

    /** 1~3개·5종 안·중복 없음. details 키는 요청 이름(typologies) — 화면이 입력 아래에 안내. */
    static List<String> typologies(List<String> in) {
        if (in == null || in.isEmpty() || in.size() > MAX_TYPOLOGIES) {
            throw new InvalidRequestException("typologies", "유형은 1~3개를 골라야 합니다");
        }
        if (in.stream().anyMatch(t -> t == null || !TYPOLOGIES.contains(t))) {
            throw new InvalidRequestException("typologies", "고를 수 없는 유형입니다");
        }
        if (new HashSet<>(in).size() != in.size()) {
            throw new InvalidRequestException("typologies", "같은 유형을 두 번 골랐습니다");
        }
        return List.copyOf(in);
    }

    /**
     * 앞뒤 공백을 지우고 빈 값이면 null(유형만으로 찾음, 설계 A 9-3). 공백은 Java 공백 + 유니코드 공백 문자(NBSP 등) —
     * AI(파이썬 str.strip)가 공백으로 보는 것을 백엔드가 통과시키면 AI 422 → 화면 502 라서(#178 리뷰).
     * 글자 수는 NFC 뒤 UTF-16 이고, 센 그 NFC 문자열을 보낸다 — 두 쪽이 같은 글자를 보게(#178 리뷰).
     */
    static String query(String in) {
        if (in == null) {
            return null;
        }
        String q = Normalizer.normalize(stripSpaces(in), Normalizer.Form.NFC);
        if (q.isEmpty()) {
            return null;
        }
        if (q.length() > QUERY_MAX) {
            throw new InvalidRequestException("query", "검색어는 200자 이하여야 합니다");
        }
        return q;
    }

    /** 앞뒤의 공백을 지운다 — Character.isWhitespace 또는 isSpaceChar(NBSP·U+2007·U+202F 포함). */
    private static String stripSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start));
        while (end > start && isSpace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
