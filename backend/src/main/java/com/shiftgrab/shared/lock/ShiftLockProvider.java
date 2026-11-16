package com.shiftgrab.shared.lock;

import java.util.function.Supplier;

public interface ShiftLockProvider {
    <T> T executeWithLock(Long shiftId, Supplier<T> task);
}
