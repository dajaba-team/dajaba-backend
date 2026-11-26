-- SCRUM-430: KYC 확장판(HI-Small_STR_expanded, 166,207명)은 신규 고객 164,538명의 계좌 개설 목적(서식 IV-88)을 비워 둔다.
-- 없는 값은 지어내지 않고 NULL 로 둔다. AI(Customer.account_purpose: str | None)·PDF(88번 "원천 미제공")는 이미 NULL 을 처리한다.
-- 업종·위험등급·고객 등록일·국가코드는 그대로 필수.
alter table ops.customer_attributes alter column account_purpose drop not null;

-- 값이 있거나 NULL 둘 중 하나 — 빈 문자열·공백만 있는 값은 DB가 막는다(#190 리뷰).
-- CSV COPY 에서 따옴표 친 ""는 NULL 이 아니라 빈 문자열이 되고, AI 는 빈 코드를 라벨 사전에서 찾다 초안 생성이 깨진다.
alter table ops.customer_attributes
    add constraint ck_customer_attributes_account_purpose_not_blank
    check (account_purpose is null or btrim(account_purpose) <> '');
