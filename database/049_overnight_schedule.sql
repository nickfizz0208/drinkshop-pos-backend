-- 049: End times earlier than the start time refer to the following day.
SET @same_day_check := (
  SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='shift_template'
    AND tc.CONSTRAINT_TYPE='CHECK' AND REGEXP_REPLACE(LOWER(cc.CHECK_CLAUSE),'[[:space:]`()]+','')='end_time>start_time'
);
SET @change_check := IF(@same_day_check IS NULL, 'DO 0',
  CONCAT('ALTER TABLE shift_template DROP CHECK `', REPLACE(@same_day_check, '`', '``'),
    '`, ADD CONSTRAINT chk_shift_template_distinct_times CHECK (end_time <> start_time)'));
PREPARE change_check FROM @change_check;
EXECUTE change_check;
DEALLOCATE PREPARE change_check;

SET @same_day_check := (
  SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='staff_schedule'
    AND tc.CONSTRAINT_TYPE='CHECK' AND REGEXP_REPLACE(LOWER(cc.CHECK_CLAUSE),'[[:space:]`()]+','')='end_time>start_time'
);
SET @change_check := IF(@same_day_check IS NULL, 'DO 0',
  CONCAT('ALTER TABLE staff_schedule DROP CHECK `', REPLACE(@same_day_check, '`', '``'),
    '`, ADD CONSTRAINT chk_staff_schedule_distinct_times CHECK (end_time <> start_time)'));
PREPARE change_check FROM @change_check;
EXECUTE change_check;
DEALLOCATE PREPARE change_check;

SET @same_day_check := (
  SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='staff_schedule_published'
    AND tc.CONSTRAINT_TYPE='CHECK' AND REGEXP_REPLACE(LOWER(cc.CHECK_CLAUSE),'[[:space:]`()]+','')='end_time>start_time'
);
SET @change_check := IF(@same_day_check IS NULL, 'DO 0',
  CONCAT('ALTER TABLE staff_schedule_published DROP CHECK `', REPLACE(@same_day_check, '`', '``'),
    '`, ADD CONSTRAINT chk_staff_schedule_published_distinct_times CHECK (end_time <> start_time)'));
PREPARE change_check FROM @change_check;
EXECUTE change_check;
DEALLOCATE PREPARE change_check;
