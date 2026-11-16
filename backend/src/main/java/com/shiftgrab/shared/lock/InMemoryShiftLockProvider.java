package com.shiftgrab.shared.lock;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component
public class InMemoryShiftLockProvider implements ShiftLockProvider {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T executeWithLock(Long shiftId, Supplier<T> task) {
        ReentrantLock lock = locks.computeIfAbsent(shiftId, id -> new ReentrantLock());
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }
}
