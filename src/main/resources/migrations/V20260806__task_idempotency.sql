ALTER TABLE trip_task ADD COLUMN plan_id VARCHAR(64) NULL AFTER task_type;
ALTER TABLE trip_task ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER plan_id;
CREATE UNIQUE INDEX uk_trip_task_idempotency ON trip_task (owner_id, task_type, idempotency_key);
CREATE INDEX idx_trip_task_plan_active ON trip_task (plan_id, task_type, status, deleted);
