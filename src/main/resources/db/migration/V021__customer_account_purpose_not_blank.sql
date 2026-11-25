-- SCRUM-430 (#190 리뷰): 계좌 개설 목적은 값이 있거나 NULL 둘 중 하나 — 빈 문자열·공백만 있는 값은 DB가 막는다.
-- CSV COPY 에서 따옴표 친 ""는 NULL 이 아니라 빈 문자열이 되고, AI 는 빈 코드를 라벨 사전에서 찾다 초안 생성이 깨진다.
-- V020 은 이미 적용한 DB(데스크톱 시연 DB)가 있어 고치지 않고 이 파일로 나눴다.
alter table ops.customer_attributes
    add constraint ck_customer_attributes_account_purpose_not_blank
    check (account_purpose is null or btrim(account_purpose) <> '');
