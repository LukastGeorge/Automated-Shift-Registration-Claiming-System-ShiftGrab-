package com.shiftgrab.shift;

/** A manager created a recurring shift template (SG-23); consumed by audit (SG-52). */
public record ShiftTemplateCreated(long templateId, long actorId, String name) {
}
