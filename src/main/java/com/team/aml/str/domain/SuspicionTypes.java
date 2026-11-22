package com.team.aml.str.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 서식 VI "의심스러운 거래유형" 이름표 — 5분류 38항목, <b>서식 순서 그대로</b>.
 * 지금까지 이름표가 프론트(src/shared/str/suspicionTypes.js)에만 있어 확정본 PDF 에 코드(DEMO_VI2_09)가 그대로 찍혔다(11/21 리뷰).
 * 프론트와 같은 코드·분류·이름을 그대로 옮겼다 — 출처: UI/UX 검토 자료 v1.3 "첨부 미확정 서식 참고안"(11/7).
 *
 * <ul>
 *   <li>코드(DEMO_ 접두사)는 아직 확정 전이다. 룰 명세의 "서식 유형 번호"가 정해지면 <b>프론트 목록과 같이</b> 바꾼다</li>
 *   <li>모르는 코드는 숨기지 않는다 — 프론트와 같은 "확인 필요한 유형 (코드)"</li>
 *   <li>DB(str_drafts·str_reports.suspicion_types)에는 코드만 둔다. 이름표는 그릴 때만 붙인다</li>
 *   <li>찾기는 코드를 키로 한 맵 하나({@link #find}) — 그릴 때마다 목록을 훑지 않고, 나중에 제출 검증도 같은 맵을 쓴다</li>
 * </ul>
 */
public final class SuspicionTypes {

    /** 서식 VI 의 다섯 분류 — 제목을 같이 들고 있어 분류 키를 잘못 쓰면 컴파일에서 걸린다(11/21 리뷰 7). */
    public enum Group {
        VI_1("VI-1. 금융사고 또는 긴급조사가 요청되는 사건 관련"),
        VI_2("VI-2. 의심스러운 거래자의 태도 및 특징 관련"),
        VI_3("VI-3. 계좌 정보 관련"),
        VI_4("VI-4. 거래유형 관련"),
        VI_5("VI-5. 기타 특징 및 유형");

        private final String title;

        Group(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** 한 항목. */
    public record Entry(String code, Group group, String label) {

        public Entry {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(label, "label");
        }
    }

    /** 서식 순서. */
    public static final List<Entry> ALL = List.of(
            // VI-1. 금융사고 또는 긴급조사가 요청되는 사건 관련
            new Entry("DEMO_VI1_01", Group.VI_1, "금융사고 거액횡령 의심"),
            new Entry("DEMO_VI1_02", Group.VI_1, "뇌물 공여·수수"),
            new Entry("DEMO_VI1_03", Group.VI_1, "거액 외화도피"),
            new Entry("DEMO_VI1_04", Group.VI_1, "조직범죄·강력사건 연루"),
            new Entry("DEMO_VI1_05", Group.VI_1, "테러자금"),
            new Entry("DEMO_VI1_06", Group.VI_1, "기타 긴급조사 필요 거래"),
            new Entry("DEMO_VI1_07", Group.VI_1, "수사기관 자료제공 요청 거래"),
            // VI-2. 의심스러운 거래자의 태도 및 특징 관련
            new Entry("DEMO_VI2_01", Group.VI_2, "실명노출 기피"),
            new Entry("DEMO_VI2_02", Group.VI_2, "합당한 답변 불제공 또는 자금출처 불명"),
            new Entry("DEMO_VI2_03", Group.VI_2, "업력·규모 대비 과다 거래"),
            new Entry("DEMO_VI2_04", Group.VI_2, "언행과 거래금액의 부조화"),
            new Entry("DEMO_VI2_05", Group.VI_2, "중요인물 관련 거래"),
            new Entry("DEMO_VI2_06", Group.VI_2, "어색하고 불안한 태도"),
            new Entry("DEMO_VI2_07", Group.VI_2, "사전거래 없는 고객의 의심거래 요청"),
            new Entry("DEMO_VI2_08", Group.VI_2, "의심스러운 동행자"),
            new Entry("DEMO_VI2_09", Group.VI_2, "불특정다수와의 거래"),
            new Entry("DEMO_VI2_10", Group.VI_2, "계약자·수익자의 빈번하거나 갑작스러운 변경"),
            // VI-3. 계좌 정보 관련
            new Entry("DEMO_VI3_01", Group.VI_3, "타인 명의 또는 계좌 이용"),
            new Entry("DEMO_VI3_02", Group.VI_3, "특별한 사유 없는 복수 계좌 개설"),
            new Entry("DEMO_VI3_03", Group.VI_3, "의심스러운 계약·계좌 해지"),
            new Entry("DEMO_VI3_04", Group.VI_3, "단발성 계좌 이용"),
            // VI-4. 거래유형 관련
            new Entry("DEMO_VI4_01", Group.VI_4, "갑작스러운 거래패턴 변화"),
            new Entry("DEMO_VI4_02", Group.VI_4, "원격지거래"),
            new Entry("DEMO_VI4_03", Group.VI_4, "교환거래"),
            new Entry("DEMO_VI4_04", Group.VI_4, "분할거래"),
            new Entry("DEMO_VI4_05", Group.VI_4, "현금 집착 거래"),
            new Entry("DEMO_VI4_06", Group.VI_4, "거액 입금 후 당일·익일 인출"),
            new Entry("DEMO_VI4_07", Group.VI_4, "무기명증서 관련거래"),
            new Entry("DEMO_VI4_08", Group.VI_4, "계좌개설 없는 거액 환전 또는 외국 송금"),
            new Entry("DEMO_VI4_09", Group.VI_4, "의심스러운 담보대출 또는 보험 즉시 약관대출"),
            new Entry("DEMO_VI4_10", Group.VI_4, "주금 납입거래 또는 예금잔액증명서 발급 요청"),
            new Entry("DEMO_VI4_11", Group.VI_4, "다중거래 동시요청"),
            new Entry("DEMO_VI4_12", Group.VI_4, "빈번한 입출금(입출고)"),
            new Entry("DEMO_VI4_13", Group.VI_4, "의심스러운 대여금고 또는 보호예수 거래"),
            new Entry("DEMO_VI4_14", Group.VI_4, "법인·타인자산 담보 거래"),
            new Entry("DEMO_VI4_15", Group.VI_4, "무관업종 또는 의심스러운 재산에 대한 보험청약"),
            new Entry("DEMO_VI4_16", Group.VI_4, "테러자금 의심 거래"),
            // VI-5. 기타 특징 및 유형
            new Entry("DEMO_VI5_01", Group.VI_5, "기타 (특징·유형을 따로 기술)")
    );

    /** 코드 → 항목. 코드가 겹치면 클래스가 올라가지 않는다(IllegalStateException). */
    private static final Map<String, Entry> BY_CODE = index(ALL);

    /** 코드 → 서식 순서(ALL 의 자리). */
    private static final Map<String, Integer> ORDER = order(ALL);

    private SuspicionTypes() {}

    /** 이 목록에 있는 코드면 그 항목. */
    public static Optional<Entry> find(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }

    /** 고른 코드 중 아는 것을 <b>서식 순서로</b>. 같은 코드가 두 번 와도 한 번. */
    public static List<Entry> known(Collection<String> codes) {
        return new LinkedHashSet<>(codes).stream()
                .map(BY_CODE::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(e -> ORDER.get(e.code())))
                .toList();
    }

    /** 고른 코드 중 이 목록에 없는 것 — 들어온 순서 그대로, 중복 없이. */
    public static List<String> unknown(Collection<String> codes) {
        List<String> out = new ArrayList<>();
        for (String c : new LinkedHashSet<>(codes)) {
            if (!BY_CODE.containsKey(c)) out.add(c);
        }
        return out;
    }

    /** 모르는 코드의 표기 — 프론트 suspicionTypeLabel 과 같은 글자. */
    public static String unknownLabel(String code) {
        return "확인 필요한 유형 (" + code + ")";
    }

    private static Map<String, Entry> index(List<Entry> all) {
        Map<String, Entry> m = new HashMap<>();
        for (Entry e : all) {
            if (m.put(e.code(), e) != null) {
                throw new IllegalStateException("의심 유형 코드가 겹친다: " + e.code());
            }
        }
        return Map.copyOf(m);
    }

    private static Map<String, Integer> order(List<Entry> all) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            m.put(all.get(i).code(), i);
        }
        return Map.copyOf(m);
    }
}
