-- SG-23 AC3: generating a template twice must never duplicate a shift. The generator inserts with
-- ON CONFLICT DO NOTHING on this index, which also covers two managers generating at the same moment.
-- One-off shifts (template_id IS NULL) are not constrained.
CREATE UNIQUE INDEX uq_shifts_template_start ON shifts (template_id, start_at) WHERE template_id IS NOT NULL;
