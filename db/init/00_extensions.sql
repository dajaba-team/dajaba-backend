-- PostgreSQL 컨테이너 첫 기동 때 한 번만 실행된다(볼륨이 비어 있을 때). 01_roles.sh 보다 먼저(파일 이름 순서).
-- 확장 생성은 superuser 만 된다 — app_migrate·app_ai 로는 못 만들어서 여기서 postgres 로 만든다.
-- 이미 있는 볼륨에는 이 파일이 안 돈다 → README "pgvector" 의 docker exec 명령을 손으로 1회.
create extension if not exists vector;
