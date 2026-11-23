-- SG-80: after a flash-sale run, the database itself must agree with the HTTP answers.
-- psql -U shiftgrab -d shiftgrab -f verify-db.sql
SELECT s.name,
       s.max_slots,
       s.booked_slots,
       count(r.id) FILTER (WHERE r.status = 'CONFIRMED')                        AS confirmed_rows,
       s.booked_slots <= s.max_slots                                            AS no_overbooking,
       s.booked_slots = count(r.id) FILTER (WHERE r.status = 'CONFIRMED')       AS counter_matches_rows
  FROM shifts s
  LEFT JOIN shift_registrations r ON r.shift_id = s.id
 WHERE s.name LIKE 'Flash sale %'
 GROUP BY s.id
 ORDER BY s.id;

-- Nobody holds the same shift twice.
SELECT count(*) AS duplicate_claims
  FROM (SELECT shift_id, user_id FROM shift_registrations WHERE status = 'CONFIRMED'
         GROUP BY shift_id, user_id HAVING count(*) > 1) d;
