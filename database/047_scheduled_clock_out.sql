-- Existing punches keep their original values. Apply schema before starting this backend.
ALTER TABLE shift_staff
  ADD COLUMN clock_out_source VARCHAR(24) NULL;
