/**
 * RabbitMQ 공통 설정 (SCRUM-276, 운영 도구 구성안 3.x). 배치 잇기(준혁)·케이스 생성(지윤)·확정본 PDF(채영)가 같이 쓴다.
 * 쓰는 법은 docs/03_shared.md §13.
 * <ul>
 *   <li><b>메시지에는 작업 ID만</b>(batch_run_id·draft_id). 처리 대상·상태는 받는 쪽이 DB에서 읽는다 — 원본은 PostgreSQL 하나.</li>
 *   <li>큐 이름은 {@link JobQueues}에 한 줄 추가하면 교환기·DLQ·바인딩이 같이 선언된다. 이름 규칙 {@code batch.*}·{@code case.*}·{@code str.pdf.*}.</li>
 *   <li>기본은 꺼짐({@code AML_RABBITMQ_ENABLED=false}). 꺼져 있거나 브로커가 죽으면 {@link JobPublisher#publish}가 false —
 *       부르는 쪽이 기존 동기 경로로 돌린다(fail-open).</li>
 * </ul>
 */
package com.team.aml.shared.messaging;
