-- SG-13: a duplicate claim of the SAME shift also overlaps itself, and PostgreSQL checked the exclusion
-- constraint before uq_registrations_active, so a double-click race surfaced as SHIFT_OVERLAP instead of
-- ALREADY_REGISTERED. Restricting the exclusion to *different* shifts gives each rule exactly one constraint:
--   same shift, active      -> uq_registrations_active      (BR-02, ALREADY_REGISTERED)
--   other shift, overlapping -> ex_registrations_no_overlap  (BR-03, SHIFT_OVERLAP)
-- btree_gist provides the <> operator for BIGINT.
ALTER TABLE shift_registrations DROP CONSTRAINT ex_registrations_no_overlap;

ALTER TABLE shift_registrations ADD CONSTRAINT ex_registrations_no_overlap
    EXCLUDE USING gist (user_id WITH =, shift_id WITH <>, time_range WITH &&) WHERE (status = 'CONFIRMED');
