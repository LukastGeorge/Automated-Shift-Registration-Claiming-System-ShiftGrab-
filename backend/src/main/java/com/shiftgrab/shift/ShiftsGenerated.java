package com.shiftgrab.shift;

import java.util.List;

/** Published once per template generation (SG-23) with the ids of the shifts actually created. */
public record ShiftsGenerated(long templateId, long actorId, List<Long> shiftIds) {

    public ShiftsGenerated {
        shiftIds = List.copyOf(shiftIds);
    }
}
