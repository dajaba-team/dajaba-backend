package com.team.aml.str.api;

import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrReportPdfMessage;
import com.team.aml.str.application.GenerateStrReportPdf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StrReportPdfMessageListenerTest {

    private final GenerateStrReportPdf generate = mock(GenerateStrReportPdf.class);
    private final StrReportPdfMessageListener listener = new StrReportPdfMessageListener(generate);

    @Test
    void 정상_메시지의_draft_id로_PDF_생성을_호출한다() {
        listener.onMessage(new StrReportPdfMessage(42L));
        verify(generate).execute(42L);
    }

    @Test
    void 정상_재시도_소진시_PENDING을_FAILED로_바꾼다() {
        listener.onGiveUp(new StrReportPdfMessage(42L));
        verify(generate).fail(42L, GenerateStrReportPdf.QUEUE_GIVE_UP);
    }

    @Test
    void 정상_공통_작업큐와_메시지_타입을_사용한다() {
        assertThat(listener.queue()).isEqualTo(JobQueues.STR_PDF_GENERATE);
        assertThat(listener.messageType()).isEqualTo(StrReportPdfMessage.class);
    }
}
