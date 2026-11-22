package com.team.aml.shared.messaging;

import java.util.List;

/**
 * 작업 큐 이름 한곳 (SCRUM-276). 새 큐는 상수 하나 + {@link #ALL}에 한 줄 — {@link RabbitMessagingConfig}가
 * 큐·DLQ({@code <큐>.dlq})·바인딩을 선언한다. 라우팅 키 = 큐 이름.
 * 이름 규칙(README 운영 도구 표): 배치 잇기 {@code batch.*} · 케이스 {@code case.*} · 확정본 PDF {@code str.pdf.*}.
 */
public final class JobQueues {

    /** 작업 메시지를 받는 direct 교환기. */
    public static final String EXCHANGE = "aml.jobs";
    /** 재시도를 다 쓴 메시지가 가는 교환기 → {@code <큐>.dlq}. */
    public static final String DEAD_LETTER_EXCHANGE = "aml.jobs.dlx";
    public static final String DLQ_SUFFIX = ".dlq";

    /** 케이스 생성 단계 — 메시지 {@link BatchRunMessage}(OPEN_CASES batch_run_id). 담당: 지윤 */
    public static final String CASE_OPEN_CASES = "case.open-cases";

    /** STR 확정본 PDF 생성 — 메시지 {@link StrReportPdfMessage}(draft_id). */
    public static final String STR_PDF_GENERATE = "str.pdf.generate";

    /** 선언할 큐 전부. 큐를 추가하면 여기에도. */
    public static final List<String> ALL = List.of(CASE_OPEN_CASES, STR_PDF_GENERATE);

    private JobQueues() {
    }

    public static String deadLetterQueue(String queue) {
        return queue + DLQ_SUFFIX;
    }
}
