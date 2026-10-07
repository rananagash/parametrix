package com.parametrix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Pattern;

@Component
public class CadRunner {
    private final String executable;
    private final int timeout;
    private static final Pattern EXTERNAL = Pattern.compile("\\b(include|use)\\s*<|\\b(import|surface)\\s*\\(");
    public record Result(boolean success, String diagnostics, Path mesh) {}
    public CadRunner(@Value("${parametrix.openscad}") String executable, @Value("${parametrix.render-timeout}") int timeout) {
        this.executable = executable; this.timeout = timeout;
    }
    public static void validate(String source) {
        if (source == null || source.isBlank() || source.length() > 100_000) throw new IllegalArgumentException("Source must contain 1–100,000 characters.");
        // Preserve strings so file-reading calls cannot be hidden by comment-like text in literals.
        StringBuilder cleaned = new StringBuilder();
        boolean string = false, line = false, block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) { if (c == '\n') { line = false; cleaned.append(c); } continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; cleaned.append(' '); } continue; }
            if (string) { cleaned.append(' '); if (c == '\\') { i++; } else if (c == '"') string = false; continue; }
            if (c == '"') { string = true; cleaned.append(' '); }
            else if (c == '/' && next == '/') { line = true; i++; cleaned.append(' '); }
            else if (c == '/' && next == '*') { block = true; i++; cleaned.append(' '); }
            else cleaned.append(c);
        }
        if (EXTERNAL.matcher(cleaned).find()) throw new IllegalArgumentException("External file operations (include, use, import, surface) are unsupported.");
    }
    public Result render(String source, Path directory, BooleanSupplier cancelled, Consumer<String> log) {
        Process process = null;
        try {
            validate(source);
            Files.createDirectories(directory);
            Path input = directory.resolve("model.scad"), output = directory.resolve("model.stl");
            Files.writeString(input, source);
            process = new ProcessBuilder(executable, "--export-format", "binstl", "-o", output.toString(), input.toString())
                    .directory(directory.toFile()).start();
            final Process running = process;
            var readers = Executors.newVirtualThreadPerTaskExecutor();
            try {
                Future<String> stdout = readers.submit(() -> drain(running.getInputStream(), log));
                Future<String> stderr = readers.submit(() -> drain(running.getErrorStream(), log));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
                String failure = null;
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) { failure = "Render cancelled."; break; }
                    if (System.nanoTime() >= deadline) { failure = "Render timed out after " + timeout + " seconds."; break; }
                }
                if (failure != null) kill(process);
                String diagnostics = stdout.get(5, TimeUnit.SECONDS) + stderr.get(5, TimeUnit.SECONDS);
                if (failure != null) return new Result(false, failure + "\n" + diagnostics, null);
                if (cancelled.getAsBoolean()) return new Result(false, "Render cancelled.", null);
                if (process.exitValue() != 0 || diagnostics.contains("ERROR:") || !validStl(output))
                    return new Result(false, "OpenSCAD failed or produced empty/invalid geometry.\n" + diagnostics, null);
                return new Result(true, diagnostics, output);
            } finally {
                if (process.isAlive()) kill(process);
                readers.shutdownNow();
            }
        } catch (IllegalArgumentException e) { return new Result(false, e.getMessage(), null);
        } catch (Exception e) { return new Result(false, "OpenSCAD execution failed: " + e.getMessage(), null);
        } finally { if (process != null && process.isAlive()) kill(process); }
    }
    private static String drain(InputStream input, Consumer<String> log) throws IOException {
        StringBuilder captured = new StringBuilder();
        try (Reader reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048]; int count;
            while ((count = reader.read(buffer)) != -1) {
                int remaining = 32_768 - captured.length();
                if (remaining > 0) { String chunk = new String(buffer, 0, Math.min(count, remaining)); captured.append(chunk); log.accept(chunk); }
            }
        }
        return captured.toString();
    }
    static void kill(Process process) {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public static boolean validStl(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < 134 || Files.size(path) > 50_000_000) return false;
        try (var channel = java.nio.channels.FileChannel.open(path)) {
            ByteBuffer header = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining() && channel.read(header) != -1) {}
            if (header.hasRemaining()) return false;
            long faces = Integer.toUnsignedLong(header.getInt(80));
            if (faces == 0 || Files.size(path) != 84 + 50 * faces) return false;
            ByteBuffer triangle = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN);
            boolean nondegenerate = false;
            for (long n = 0; n < faces; n++) {
                triangle.clear(); while (triangle.hasRemaining() && channel.read(triangle) != -1) {}
                if (triangle.hasRemaining()) return false;
                triangle.flip(); float[] values = new float[12];
                for (int j = 0; j < 12; j++) { values[j] = triangle.getFloat(); if (!Float.isFinite(values[j])) return false; }
                double ax = (double) values[6]-values[3], ay = (double) values[7]-values[4], az = (double) values[8]-values[5];
                double bx = (double) values[9]-values[3], by = (double) values[10]-values[4], bz = (double) values[11]-values[5];
                if (Math.abs(ay*bz-az*by) + Math.abs(az*bx-ax*bz) + Math.abs(ax*by-ay*bx) > 0) nondegenerate = true;
            }
            return nondegenerate;
        }
    }
}
