package com.team.aml.str.api;

import com.team.aml.shared.messaging.JobGiveUpHandler;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrReportPdfMessage;
import com.team.aml.str.application.GenerateStrReportPdf;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RabbitMQ에서 STR 확정본 PDF 작업을 받는다. 정상 반환은 ACK, 처리 예외는 공통 재시도·DLQ 정책을 따른다. */
@Component
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
public class StrReportPdfMessageListener implements JobGiveUpHandler<StrReportPdfMessage> {

    private final GenerateStrReportPdf generate;

    public StrReportPdfMessageListener(GenerateStrReportPdf generate) {
        this.generate = generate;
    }

    @RabbitListener(queues = JobQueues.STR_PDF_GENERATE)
    public void onMessage(StrReportPdfMessage message) {
        generate.execute(message.draftId());
    }

    @Override
    public String queue() {
        return JobQueues.STR_PDF_GENERATE;
    }

    @Override
    public Class<StrReportPdfMessage> messageType() {
        return StrReportPdfMessage.class;
    }

    /** DB 장애 등으로 재시도를 모두 소진하면 PENDING을 FAILED로 바꿔 승인자가 P5로 복구할 수 있게 한다. */
    @Override
    public void onGiveUp(StrReportPdfMessage message) {
        generate.fail(message.draftId(), GenerateStrReportPdf.QUEUE_GIVE_UP);
    }
}
