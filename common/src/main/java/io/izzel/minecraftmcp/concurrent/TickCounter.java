package io.izzel.minecraftmcp.concurrent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Counts real game ticks, fed by the loaders' end-of-tick events, and runs work once per tick on the game thread. */
public final class TickCounter {
    public static final TickCounter SERVER = new TickCounter();
    public static final TickCounter CLIENT = new TickCounter();

    private final Object lock = new Object();
    private final List<BooleanSupplier> tasks = new CopyOnWriteArrayList<>();
    private long ticks;

    /** Called on the game thread at the end of every tick. */
    public void onTick() {
        for (BooleanSupplier task : tasks) {
            boolean done;
            try {
                done = task.getAsBoolean();
            } catch (RuntimeException e) {
                done = true; // a task reports its own failure; a throwing one must not run forever
            }
            if (done) tasks.remove(task);
        }
        synchronized (lock) {
            ticks++;
            lock.notifyAll();
        }
    }

    /** Runs {@code task} at the end of each tick, on the game thread, until it returns true. */
    public void eachTick(BooleanSupplier task) {
        tasks.add(task);
    }

    public long current() {
        synchronized (lock) {
            return ticks;
        }
    }

    /** Waits for {@code count} ticks or {@code timeoutMs}, whichever is first; returns the ticks that passed. */
    public long await(long count, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        synchronized (lock) {
            long start = ticks;
            long target = start + Math.max(0, count);
            while (ticks < target) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) break;
                try {
                    lock.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting " + count + " ticks", e);
                }
            }
            return ticks - start;
        }
    }
}
