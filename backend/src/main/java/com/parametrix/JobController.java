package com.parametrix;

import java.util.Map;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(
    origins = {"http://localhost:3000", "http://127.0.0.1:3000"},
    allowedHeaders = {"Content-Type", "Last-Event-ID"},
    methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
public class JobController {
  private final Jobs jobs;

  public JobController(Jobs jobs) {
    this.jobs = jobs;
  }

  public record Request(String prompt, String source) {}

  @PostMapping
  public ResponseEntity<Map<String, String>> submit(@RequestBody Request request) {
    boolean prompt = request.prompt() != null && !request.prompt().isBlank();
    boolean source = request.source() != null && !request.source().isBlank();
    if (prompt == source
        || (prompt && request.prompt().length() > 10_000)
        || (source && request.source().length() > 100_000))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Provide either a prompt (up to 10,000 characters) or source (up to 100,000"
              + " characters).");
    var job = jobs.submit(prompt ? request.prompt() : null, source ? request.source() : null);
    return ResponseEntity.accepted().body(Map.of("id", job.id));
  }

  @GetMapping("/{id}")
  public Jobs.Snapshot status(@PathVariable String id) {
    return jobs.get(id).snapshot();
  }

  @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") String cursor) {
    try {
      return jobs.get(id).subscribe(Long.parseLong(cursor));
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event cursor.");
    }
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> cancel(@PathVariable String id) {
    jobs.cancel(id);
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/{id}/model.stl")
  public ResponseEntity<Resource> mesh(@PathVariable String id) {
    var job = jobs.get(id);
    synchronized (job) {
      if (!"succeeded".equals(job.status) || job.mesh == null)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Mesh is not ready.");
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=parametrix.stl")
          .body(new FileSystemResource(job.mesh));
    }
  }

  @GetMapping("/{id}/model.scad")
  public ResponseEntity<String> source(@PathVariable String id) {
    var job = jobs.get(id);
    synchronized (job) {
      if (job.source.isBlank())
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Source is not ready.");
      return ResponseEntity.ok()
          .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=parametrix.scad")
          .body(job.source);
    }
  }
}
