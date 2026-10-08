package com.parametrix;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class Jobs {
  public record Event(long id, String type, Object data) {}

  public record Snapshot(
      String id,
      String status,
      int attempt,
      String source,
      String diagnostics,
      boolean artifactAvailable) {}

  public static class Job {
    final String id = UUID.randomUUID().toString();
    final Path directory;
    String status = "queued", source = "", diagnostics = "";
    int attempt;
    volatile boolean cancelled;
    Instant finished;
    Path mesh;
    final List<Event> events = new ArrayList<>();
    final List<SseEmitter> listeners = new ArrayList<>();
    long sequence;

    Job(Path root) {
      directory = root.resolve(id);
    }

    synchronized Snapshot snapshot() {
      return new Snapshot(id, status, attempt, source, diagnostics, mesh != null);
    }

    synchronized void event(String type, Object data) {
      Event event = new Event(++sequence, type, data);
      events.add(event);
      // At most four sources and bounded compiler logs per job.
      for (SseEmitter emitter : List.copyOf(listeners))
        if (!send(emitter, event)) listeners.remove(emitter);
    }

    static boolean send(SseEmitter emitter, Event event) {
      try {
        emitter.send(
            SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event.data()));
        return true;
      } catch (IOException | IllegalStateException e) {
        emitter.complete();
        return false;
      }
    }

    synchronized void state(String value) {
      status = value;
      event("status", snapshot());
    }

    synchronized void source(String value) {
      source = value;
      event("source", Map.of("source", value, "attempt", attempt));
    }

    synchronized void finish(String value, String message) {
      if (finished != null) return;
      diagnostics = message;
      status = value;
      finished = Instant.now();
      event("complete", snapshot());
      var closing = List.copyOf(listeners);
      listeners.clear();
      closing.forEach(SseEmitter::complete);
    }

    synchronized SseEmitter subscribe(long after) {
      if (after < 0 || after > sequence)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event cursor.");
      SseEmitter emitter = new SseEmitter(0L);
      for (Event event : events) if (event.id() > after && !send(emitter, event)) return emitter;
      if (finished != null) emitter.complete();
      else {
        listeners.add(emitter);
        Runnable remove =
            () -> {
              synchronized (this) {
                listeners.remove(emitter);
              }
            };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
      }
      return emitter;
    }

    synchronized void heartbeat() {
      listeners.removeIf(
          emitter -> {
            try {
              emitter.send(SseEmitter.event().comment("heartbeat"));
              return false;
            } catch (IOException | IllegalStateException e) {
              emitter.complete();
              return true;
            }
          });
    }
  }

  private final AiClient ai;
  private final CadRunner cad;
  private final Path root;
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Job active;

  public Jobs(AiClient ai, CadRunner cad) throws IOException {
    this.ai = ai;
    this.cad = cad;
    root = Files.createTempDirectory("parametrix-");
  }

  public synchronized Job submit(String prompt, String source) {
    if (active != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A job is already running.");
    Job job = new Job(root);
    jobs.put(job.id, job);
    active = job;
    job.event("status", job.snapshot());
    worker.submit(() -> run(job, prompt, source));
    return job;
  }

  private void run(Job job, String prompt, String source) {
    try {
      int max = source == null ? 4 : 1;
      String previous = null, diagnostics = null;
      for (int attempt = 1; attempt <= max; attempt++) {
        if (job.cancelled) {
          job.finish("cancelled", "Job cancelled.");
          return;
        }
        synchronized (job) {
          job.attempt = attempt;
        }
        if (source == null) {
          job.state(attempt == 1 ? "generating" : "repairing");
          job.event(
              "log",
              Map.of(
                  "message",
                  attempt == 1
                      ? "Generating OpenSCAD code…"
                      : "Repairing geometry (attempt " + attempt + ")…"));
          previous = ai.generate(prompt, previous, diagnostics);
        } else previous = source;
        if (job.cancelled) {
          job.finish("cancelled", "Job cancelled.");
          return;
        }
        job.source(previous);
        job.state("compiling");
        job.event("log", Map.of("message", "Compiling geometry…"));
        var result =
            cad.render(
                previous,
                job.directory.resolve("attempt-" + attempt),
                () -> job.cancelled,
                text -> job.event("log", Map.of("message", text)));
        if (job.cancelled) {
          job.finish("cancelled", "Job cancelled.");
          return;
        }
        if (result.success()) {
          synchronized (job) {
            job.mesh = result.mesh();
          }
          job.event("log", Map.of("message", "Compilation successful. Mesh ready."));
          job.finish("succeeded", result.diagnostics());
          return;
        }
        diagnostics = result.diagnostics();
        synchronized (job) {
          job.diagnostics = diagnostics;
        }
        job.event("log", Map.of("message", diagnostics));
      }
      job.finish("failed", diagnostics);
    } catch (Exception e) {
      job.finish(
          job.cancelled ? "cancelled" : "failed",
          job.cancelled ? "Job cancelled." : e.getMessage());
    } finally {
      synchronized (this) {
        if (active == job) active = null;
      }
    }
  }

  public Job get(String id) {
    Job job = jobs.get(id);
    if (job == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found or expired.");
    return job;
  }

  public void cancel(String id) {
    Job job = get(id);
    synchronized (job) {
      if (job.finished == null) {
        job.cancelled = true;
        job.state("cancelling");
      }
    }
  }

  @Scheduled(fixedRate = 15_000)
  public void maintenance() {
    for (Job job : jobs.values()) {
      job.heartbeat();
      synchronized (job) {
        if (job.finished != null && job.finished.isBefore(Instant.now().minusSeconds(3600))) {
          jobs.remove(job.id);
          delete(job.directory);
        }
      }
    }
  }

  static void delete(Path path) {
    if (!Files.exists(path)) return;
    try (var files = Files.walk(path)) {
      files
          .sorted(Comparator.reverseOrder())
          .forEach(
              file -> {
                try {
                  Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }

  @PreDestroy
  public void close() {
    jobs.values().forEach(job -> job.cancelled = true);
    worker.shutdown();
    try {
      if (!worker.awaitTermination(65, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException e) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
    delete(root);
  }
}
