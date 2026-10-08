package com.parametrix;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class JobsTest {
  @TempDir Path temp;
  Jobs jobs;

  @AfterEach
  void close() {
    if (jobs != null) jobs.close();
  }

  CadRunner runner(int failures) {
    return new CadRunner("unused", 30) {
      int count;

      @Override
      public Result render(
          String source, Path dir, BooleanSupplier cancelled, Consumer<String> log) {
        try {
          validate(source);
        } catch (IllegalArgumentException e) {
          return new Result(false, e.getMessage(), null);
        }
        if (count++ < failures) return new Result(false, "ERROR: bad geometry", null);
        return new Result(true, "ok", temp.resolve("mesh.stl"));
      }
    };
  }

  Jobs.Snapshot waitFor(Jobs.Job job) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          while (!java.util.Set.of("failed", "succeeded", "cancelled")
              .contains(job.snapshot().status())) Thread.sleep(10);
        });
    return job.snapshot();
  }

  @Test
  void generationSucceedsAndEventsAreOrdered() throws Exception {
    jobs = new Jobs((p, s, d) -> "cube(10);", runner(0));
    var job = jobs.submit("cube", null);
    var result = waitFor(job);
    assertEquals("succeeded", result.status());
    assertEquals(1, result.attempt());
    assertTrue(result.artifactAvailable());
    synchronized (job) {
      long id = 0;
      for (var event : job.events) assertEquals(++id, event.id());
      assertEquals("complete", job.events.getLast().type());
    }
  }

  @Test
  void repairsIncludePriorSourceAndDiagnostics() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    jobs =
        new Jobs(
            (p, s, d) -> {
              if (calls.incrementAndGet() > 1) {
                assertEquals("cube(10);", s);
                assertTrue(d.contains("ERROR"));
              }
              return "cube(10);";
            },
            runner(1));
    assertEquals(2, waitFor(jobs.submit("cube", null)).attempt());
    assertEquals(2, calls.get());
  }

  @Test
  void exhaustsExactlyThreeRepairs() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    jobs =
        new Jobs(
            (p, s, d) -> {
              calls.incrementAndGet();
              return "cube(10);";
            },
            runner(99));
    var result = waitFor(jobs.submit("cube", null));
    assertEquals("failed", result.status());
    assertEquals(4, calls.get());
  }

  @Test
  void providerFailureDoesNotRetry() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    jobs =
        new Jobs(
            (p, s, d) -> {
              calls.incrementAndGet();
              throw new IllegalStateException("credentials");
            },
            runner(0));
    assertEquals("failed", waitFor(jobs.submit("cube", null)).status());
    assertEquals(1, calls.get());
  }

  @Test
  void manualInvalidSourceDoesNotCallAi() throws Exception {
    jobs =
        new Jobs(
            (p, s, d) -> {
              throw new AssertionError("AI called");
            },
            runner(0));
    var result = waitFor(jobs.submit(null, "import(\"x\");"));
    assertEquals("failed", result.status());
    assertEquals(1, result.attempt());
  }

  @Test
  void rejectsConcurrentSubmissionAndCancels() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    jobs =
        new Jobs(
            (p, s, d) -> {
              entered.countDown();
              try {
                release.await(2, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return "cube(1);";
            },
            runner(0));
    var job = jobs.submit("cube", null);
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    assertThrows(ResponseStatusException.class, () -> jobs.submit("other", null));
    jobs.cancel(job.id);
    release.countDown();
    assertEquals("cancelled", waitFor(job).status());
  }
}
