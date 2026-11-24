package com.team.aml.str.api;

import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrDraftMessage;
import com.team.aml.str.application.GenerateStrDraft;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StrDraftMessageListenerTest {

    private final GenerateStrDraft generate = mock(GenerateStrDraft.class);
    private final StrDraftMessageListener listener = new StrDraftMessageListener(generate);

    @Test
    void 정상_메시지의_draft_id로_초안_생성을_부른다() {
        listener.onMessage(new StrDraftMessage(45L));
        verify(generate).execute(45L);
    }

    @Test
    void 정상_재시도를_다_쓰면_REQUESTED를_FAILED로_닫는다() {
        listener.onGiveUp(new StrDraftMessage(45L));
        verify(generate).fail(45L, StrDraftMessageListener.QUEUE_GIVE_UP);
    }

    @Test
    void 정상_공통_작업큐와_메시지_타입을_쓴다() {
        assertThat(listener.queue()).isEqualTo(JobQueues.STR_DRAFT_GENERATE);
        assertThat(listener.messageType()).isEqualTo(StrDraftMessage.class);
    }
}
