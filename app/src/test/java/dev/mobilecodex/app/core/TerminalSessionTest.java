package dev.mobilecodex.app.core;

import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class TerminalSessionTest {
    // A real child JVM: exercises pipe flushing, newlines, process identity and exit.
    public static class Child {
        public static void main(String[] args) throws Exception {
            System.out.println("PASTE_CODE");
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
            if (!"fixture-private-code".equals(in.readLine())) System.exit(31);
            System.out.println("CODE_ACCEPTED");
            if (!"".equals(in.readLine())) System.exit(32);
            System.out.println("LOGIN_INPUT_FINISHED");
        }
    }
    private ProcessBuilder child() throws Exception {
        String binary = new File(System.getProperty("java.home"), "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
        String classes = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        return new ProcessBuilder(binary, "-cp", classes, Child.class.getName());
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.call());
    }
    @Test public void repliesGoToSameProcessAndNeverBecomeCommandsOrLoggedSecrets() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        TerminalSession terminal = new TerminalSession((name, data) -> events.add(name + data));
        try {
            JSONObject started = terminal.start(child()); String id = started.getString("id");
            await(() -> terminal.snapshot().getString("output").contains("PASTE_CODE"));
            assertThrows(IOException.class, () -> terminal.start(child()));
            assertThrows(IOException.class, () -> terminal.input("old-session", "fixture-private-code"));
            assertThrows(IOException.class, () -> terminal.input(id, "x".repeat(16385)));
            terminal.input(id, "fixture-private-code\r\n");
            await(() -> terminal.snapshot().getString("output").contains("CODE_ACCEPTED"));
            assertTrue(terminal.busy()); // Reading state again doesn't restart the process.
            assertEquals(id, terminal.snapshot().getString("id"));
            terminal.input(id, "");
            await(() -> !terminal.busy());
            assertEquals(0, terminal.snapshot().getInt("exitCode"));
            assertTrue(terminal.snapshot().getString("output").contains("LOGIN_INPUT_FINISHED"));
            assertFalse(String.join("", events).contains("fixture-private-code"));
            assertThrows(IOException.class, () -> terminal.input(id, "late-code"));
        } finally { terminal.stop(null); }
    }
    @Test public void stopAndOldSessionCannotWriteOrStopReplacementProcess() throws Exception {
        TerminalSession terminal = new TerminalSession((name, data) -> {});
        try {
            String old = terminal.start(child()).getString("id");
            terminal.stop(old); await(() -> !terminal.busy());
            String fresh = terminal.start(child()).getString("id");
            assertNotEquals(old, fresh);
            assertThrows(IOException.class, () -> terminal.input(old, "old-code"));
            assertThrows(IOException.class, () -> terminal.stop(old));
            assertTrue(terminal.busy());
            terminal.stop(fresh); await(() -> !terminal.busy());
        } finally { terminal.stop(null); }
    }

    @Test public void blockedInputDoesNotPreventStateReadsOrStop() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), released = new CountDownLatch(1);
        Process blocked = new Process() {
            private volatile boolean alive = true;
            private final OutputStream input = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    writing.countDown();
                    try { released.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    if (!alive) throw new IOException("process stopped");
                }
            };
            @Override public OutputStream getOutputStream() { return input; }
            @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public boolean isAlive() { return alive; }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { alive = false; released.countDown(); }
        };
        TerminalSession terminal = new TerminalSession((name, data) -> {});
        var process = TerminalSession.class.getDeclaredField("process"); process.setAccessible(true); process.set(terminal, blocked);
        var id = TerminalSession.class.getDeclaredField("id"); id.setAccessible(true); id.set(terminal, "blocked");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> input = workers.submit(() -> { try { terminal.input("blocked", "answer"); } catch (IOException stopped) {} });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            JSONObject snapshot = workers.submit(terminal::snapshot).get(2, TimeUnit.SECONDS);
            assertEquals("blocked", snapshot.getString("id"));
            workers.submit(() -> { terminal.stop("blocked"); return null; }).get(2, TimeUnit.SECONDS);
            input.get(2, TimeUnit.SECONDS);
            assertFalse(blocked.isAlive());
        } finally {
            blocked.destroy();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
