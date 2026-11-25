-- Fails (exit code 3 with ON_ERROR_STOP) unless the database agrees with the HTTP answers of a flash-sale run.
-- verify-db.sql prints the same facts for a person; this file is for CI and scripted runs.
--
--   psql -v ON_ERROR_STOP=1 -v expected=50 -U shiftgrab -d shiftgrab -f assert-db.sql
--
-- expected = the number of 201 answers k6 counted (claims_succeeded).
SELECT set_config('flash_sale.expected', :'expected', false);

DO $$
DECLARE
    expected   int := current_setting('flash_sale.expected')::int;
    total      int;
    overbooked int;
    mismatched int;
    duplicates int;
BEGIN
    SELECT coalesce(sum(booked_slots), 0),
           count(*) FILTER (WHERE booked_slots > max_slots)
      INTO total, overbooked
      FROM shifts WHERE name LIKE 'Flash sale %';

    SELECT count(*) INTO mismatched
      FROM shifts s
     WHERE s.name LIKE 'Flash sale %'
       AND s.booked_slots <> (SELECT count(*) FROM shift_registrations r
                               WHERE r.shift_id = s.id AND r.status = 'CONFIRMED');

    SELECT count(*) INTO duplicates
      FROM (SELECT shift_id, user_id FROM shift_registrations WHERE status = 'CONFIRMED'
             GROUP BY shift_id, user_id HAVING count(*) > 1) d;

    RAISE NOTICE 'booked=% expected=% overbooked_shifts=% counter_mismatches=% duplicate_claims=%',
        total, expected, overbooked, mismatched, duplicates;

    IF overbooked > 0 THEN RAISE EXCEPTION 'overbooking in % shift(s)', overbooked; END IF;
    IF mismatched > 0 THEN RAISE EXCEPTION 'booked_slots differs from confirmed rows in % shift(s)', mismatched; END IF;
    IF duplicates > 0 THEN RAISE EXCEPTION '% duplicate claim(s)', duplicates; END IF;
    IF total <> expected THEN
        RAISE EXCEPTION 'database holds % claims but HTTP answered 201 % times', total, expected;
    END IF;
END $$;
