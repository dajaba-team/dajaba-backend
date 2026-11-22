\set ON_ERROR_STOP on
\copy (SELECT a.alert_no,a.id AS alert_id,r.rule_id,a.score_status,a.case_opened,coalesce((SELECT string_agg(t.transaction_id::text,' ' ORDER BY t.transaction_id) FROM ops.alert_transactions t WHERE t.alert_id=a.id),'') AS transaction_ids FROM ops.alerts a JOIN ops.rules r ON r.id=a.rule_ref_id WHERE a.batch_date='2022-09-07' ORDER BY a.id) TO STDOUT WITH CSV HEADER
