\set ON_ERROR_STOP on
\copy (SELECT a.id AS alert_id,a.alert_no,r.rule_id FROM ops.cases c JOIN ops.alerts a ON a.id=c.alert_id JOIN ops.rules r ON r.id=a.rule_ref_id WHERE a.batch_date='2022-09-07' ORDER BY a.id) TO STDOUT WITH CSV HEADER
