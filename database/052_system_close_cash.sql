-- MySQL 8.0.16+. Allow system-closed shifts to await an actual cash count.
-- No historical rows are changed. Re-running this migration is safe.
SET @closing_check_drops := (
  SELECT GROUP_CONCAT(CONCAT('DROP CHECK `', REPLACE(tc.CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='daily_shift' AND tc.CONSTRAINT_TYPE='CHECK'
    AND LOWER(cc.CHECK_CLAUSE) LIKE '%status%' AND LOWER(cc.CHECK_CLAUSE) LIKE '%closed_by%'
);
SET @closing_check_sql := CONCAT('ALTER TABLE daily_shift ',
  IF(@closing_check_drops IS NULL, '', CONCAT(@closing_check_drops, ', ')),
  'ADD CONSTRAINT chk_daily_shift_closing CHECK (
    (status = ''OPEN'' AND closed_by IS NULL AND closed_at IS NULL)
    OR (status = ''CLOSED'' AND closed_at IS NOT NULL
      AND revenue_by_payment IS NOT NULL AND cups IS NOT NULL AND expected_cash IS NOT NULL
      AND ((counted_cash IS NOT NULL AND closed_by IS NOT NULL)
        OR (counted_cash IS NULL AND closed_by IS NULL AND diff_reason IS NOT NULL AND diff_reason = ''系統逾午夜自動收班''))))');
PREPARE closing_check FROM @closing_check_sql;
EXECUTE closing_check;
DEALLOCATE PREPARE closing_check;
