package com.shiftgrab.shift;

/** BR-11: {@code FULL} when every slot is booked, back to {@code OPEN} when one is released. */
public enum ShiftStatus {
    OPEN,
    FULL,
    CANCELLED;

    public static ShiftStatus forOccupancy(int bookedSlots, int maxSlots) {
        return bookedSlots >= maxSlots ? FULL : OPEN;
    }
}
