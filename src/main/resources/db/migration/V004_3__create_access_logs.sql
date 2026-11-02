-- V004_3: access_logs (레퍼런스 3.4, SCRUM-19). 코드 소속은 shared/audit.

create table ops.access_logs (
    id                 bigint generated always as identity primary key,
    user_id            bigint references ops.users(id),
    login_id_attempted varchar(64),
    target_type        varchar(16) check (target_type in ('CASE','STR_DRAFT','ALERT','USER')),
    target_id          bigint,
    action             varchar(16) not null check (action in (
        'VIEW','STR_VIEW','PDF_DOWNLOAD','EXPORT','LOGIN_OK','LOGIN_FAIL','LOGOUT',
        'USER_CREATED','ROLE_CHANGED','USER_DEACTIVATED')),
    ip                 inet,
    created_at         timestamptz not null default now()
);
create index access_logs_target_created_at_idx on ops.access_logs (target_type, target_id, created_at);
create index access_logs_user_created_at_idx on ops.access_logs (user_id, created_at);
create index access_logs_action_created_at_idx on ops.access_logs (action, created_at);
revoke update, delete on ops.access_logs from app_backend;
