package com.parametrix;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiTest {
  Jobs jobs;
  MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    jobs =
        new Jobs(
            (p, s, d) -> {
              throw new IllegalStateException("fixture failure");
            },
            new CadRunner("unused", 1));
    mvc = MockMvcBuilders.standaloneSetup(new JobController(jobs)).build();
  }

  @AfterEach
  void close() {
    jobs.close();
  }

  @Test
  void validatesRequestsAndMissingJobs() throws Exception {
    mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"x\",\"source\":\"cube(1);\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/jobs/missing")).andExpect(status().isNotFound());
  }

  @Test
  void replaysOnlyMissedEventsIncludingTerminal() throws Exception {
    var job = jobs.submit("cube", null);
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          while (job.snapshot().status().equals("queued")
              || job.snapshot().status().equals("generating")) Thread.sleep(10);
        });
    var request =
        mvc.perform(get("/api/jobs/" + job.id + "/events").header("Last-Event-ID", "1"))
            .andExpect(request().asyncStarted())
            .andReturn();
    var replay =
        mvc.perform(asyncDispatch(request))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(replay.contains("id:1\n"));
    assertTrue(replay.contains("id:2\n"));
    assertTrue(replay.contains("event:complete"));
    assertTrue(replay.contains("fixture failure"));
    mvc.perform(get("/api/jobs/" + job.id + "/events").header("Last-Event-ID", "invalid"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/jobs/" + job.id + "/model.stl")).andExpect(status().isConflict());
  }

  @Test
  void activeStreamReceivesHeartbeatAndCompletion() throws Exception {
    jobs.close();
    var release = new java.util.concurrent.CountDownLatch(1);
    jobs =
        new Jobs(
            (p, s, d) -> {
              try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              throw new IllegalStateException("done");
            },
            new CadRunner("unused", 1));
    mvc = MockMvcBuilders.standaloneSetup(new JobController(jobs)).build();
    var job = jobs.submit("cube", null);
    var request =
        mvc.perform(get("/api/jobs/" + job.id + "/events"))
            .andExpect(request().asyncStarted())
            .andReturn();
    job.heartbeat();
    release.countDown();
    request.getAsyncResult(3000);
    String body =
        mvc.perform(asyncDispatch(request))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(body.contains(":heartbeat"));
    assertTrue(body.contains("event:complete"));
  }

  @Test
  void expiresFinishedJobs() throws Exception {
    var job = jobs.submit("cube", null);
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          while (!job.snapshot().status().equals("failed")) Thread.sleep(10);
        });
    synchronized (job) {
      job.finished = java.time.Instant.now().minusSeconds(3601);
    }
    jobs.maintenance();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class, () -> jobs.get(job.id));
  }
}
