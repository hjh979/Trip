ALTER TABLE trip_plan_version ADD COLUMN snapshot_json LONGTEXT NULL AFTER result_json;
ALTER TABLE trip_plan_record ADD COLUMN snapshot_json LONGTEXT NULL AFTER result_json;
