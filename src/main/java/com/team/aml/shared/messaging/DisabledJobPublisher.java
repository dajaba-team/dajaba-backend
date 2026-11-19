package com.team.aml.shared.messaging;

/** RabbitMQ 를 끈 상태(기본). 항상 false — 부르는 쪽이 동기 경로로 돈다. */
class DisabledJobPublisher implements JobPublisher {

    @Override
    public boolean publish(String queue, Object message) {
        return false;
    }
}
