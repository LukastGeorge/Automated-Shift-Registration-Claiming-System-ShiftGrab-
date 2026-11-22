package com.shiftgrab.realtime.internal;

import java.time.Instant;

/**
 * What a browser needs to redraw one shift card (SG-40). {@code observedAt} is the database clock when the row was
 * read: updates are produced asynchronously on several instances and can arrive out of order, so clients keep the
 * newest per shift and ignore older ones.
 */
record SlotUpdate(long shiftId, int bookedSlots, int maxSlots, int availableSlots, String status,
        Instant observedAt) {
}
