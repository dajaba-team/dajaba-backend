#!/bin/sh
set -eu

failed=0
for name in GRAFANA_ADMIN_PASSWORD REDIS_PASSWORD RABBITMQ_USER RABBITMQ_PASSWORD \
    MINIO_ROOT_USER MINIO_ROOT_PASSWORD ELASTIC_PASSWORD CLICKHOUSE_PASSWORD \
    LANGFUSE_DB_PASSWORD LANGFUSE_NEXTAUTH_SECRET LANGFUSE_SALT LANGFUSE_ENCRYPTION_KEY \
    LANGFUSE_INIT_PROJECT_PUBLIC_KEY LANGFUSE_INIT_PROJECT_SECRET_KEY \
    LANGFUSE_INIT_USER_EMAIL LANGFUSE_INIT_USER_PASSWORD POSTGRES_PASSWORD; do
    # 끝의 개행도 값에 남겨 암호화 키를 정확히 검사한다.
    value=$(
        printenv "$name" || :
        printf '.'
    )
    value=${value%.}
    value=${value%?}
    if [ -z "$value" ]; then
        printf '%s\n' "$name"
        failed=1
    elif [ "$name" = LANGFUSE_ENCRYPTION_KEY ]; then
        case "$value" in
            *[!0123456789abcdefABCDEF]*)
                printf '%s\n' "$name"
                failed=1
                ;;
            *)
                if [ "${#value}" -ne 64 ]; then
                    printf '%s\n' "$name"
                    failed=1
                fi
                ;;
        esac
    fi
done
exit "$failed"
