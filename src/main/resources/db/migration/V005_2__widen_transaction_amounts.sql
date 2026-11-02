-- V005_2: 거래 금액 numeric(18,2) → numeric(20,6) (안건 19, 11/2 준혁·준영 합의, SCRUM-97).
-- HI-Small 의 Bitcoin 금액은 소수 6자리라 numeric(18,2)면 293,425개 필드 값이 바뀌고 일부는 0.00이 된다.
-- 실제 파일 정수부는 최대 13자리, 이 타입은 14자리까지 담는다. 원본 금액을 반올림하지 않는다 (ML 규약 §0).
-- 적재 전이라 행이 없어 바로 바꿀 수 있다.

alter table ops.transactions
    alter column amount_received type numeric(20,6),
    alter column amount_paid     type numeric(20,6);
