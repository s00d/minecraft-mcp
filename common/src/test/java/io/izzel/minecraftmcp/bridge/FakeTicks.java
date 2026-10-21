package io.izzel.minecraftmcp.bridge;

import io.izzel.minecraftmcp.concurrent.TickCounter;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A tick counter for fake bridges that ticks every millisecond, so tick waits finish fast in tests. */
public final class FakeTicks {
    public static final TickCounter RUNNING = new TickCounter();

    static {
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "fake-ticks");
            thread.setDaemon(true);
            return thread;
        }).scheduleAtFixedRate(RUNNING::onTick, 1, 1, TimeUnit.MILLISECONDS);
    }

    private FakeTicks() {
    }
}
