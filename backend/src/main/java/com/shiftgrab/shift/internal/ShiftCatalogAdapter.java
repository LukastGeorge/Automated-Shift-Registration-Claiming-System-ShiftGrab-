package com.shiftgrab.shift.internal;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftCatalog;

@Component
class ShiftCatalogAdapter implements ShiftCatalog {

    private final ShiftRepository shifts;

    ShiftCatalogAdapter(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    @Override
    public List<Shift> findOpeningBetween(Instant after, Instant until) {
        return shifts.findOpeningBetween(after, until);
    }
}
