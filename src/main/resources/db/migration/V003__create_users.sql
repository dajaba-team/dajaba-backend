-- V003: users + SYSTEM 행 (레퍼런스 3.4, 9.5). 개발용 사용자 4명은 여기가 아니라 DevUserSeeder(local 프로파일).

create table ops.users (
    id             bigint       generated always as identity primary key,
    login_id       varchar(64)  not null unique,
    display_name   varchar(100) not null,
    role           varchar(16)  not null check (role in ('INVESTIGATOR','APPROVER','ADMIN','SYSTEM')),
    password_hash  varchar(255),                 -- BCrypt 12. SYSTEM 은 null (로그인 불가)
    is_active      boolean      not null default true,
    created_at     timestamptz  not null default now(),
    created_by     bigint       not null,
    updated_at     timestamptz  not null default now(),
    updated_by     bigint       not null
);

-- id = 1 은 SYSTEM 으로 고정. 배치 스레드의 감사 컬럼 값 (레퍼런스 8.1)
insert into ops.users (id, login_id, display_name, role, password_hash, is_active, created_by, updated_by)
overriding system value
values (1, 'system', 'SYSTEM', 'SYSTEM', null, false, 1, 1);
select setval(pg_get_serial_sequence('ops.users', 'id'), 1, true);

-- 감사 컬럼 FK 는 users 가 생긴 뒤에 건다 (자기 참조)
alter table ops.users add constraint users_created_by_fk foreign key (created_by) references ops.users(id);
alter table ops.users add constraint users_updated_by_fk foreign key (updated_by) references ops.users(id);
