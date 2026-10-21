package io.izzel.minecraftmcp.serverlink;

import io.izzel.minecraftmcp.json.Json;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** A proxy call running on its own thread, with the request it sent and its outcome as futures: no polling. */
final class PendingCall {
    final CompletableFuture<String> sent = new CompletableFuture<>();
    final CompletableFuture<Object> result;

    PendingCall(RemoteMcpProxy proxy, String tool, long timeoutMs) {
        result = CompletableFuture.supplyAsync(() -> {
            try {
                return proxy.call(tool, Map.of(), sent::complete, timeoutMs);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, task -> {
            Thread thread = new Thread(task, "pending-call");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /** The id of the request once it went out. */
    long id() throws Exception {
        return ((Number) ((Map<?, ?>) Json.parse(sent.get(5, TimeUnit.SECONDS))).get("id")).longValue();
    }

    /** Waits for the call to end; its failure, or null when it returned. */
    Throwable failure() throws Exception {
        try {
            result.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }
}
