package com.commercesettle.democontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 준비된 별도 스택에 한정된 ramp만 실행한다. 기동·DB 준비·Worker 재생성 명령은 제공하지 않는다. */
@RestController
class LoadController {
  private final DemoSettings settings;
  private final ObjectMapper mapper;
  private final Map<UUID, Run> runs = new LinkedHashMap<>();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private boolean closing;

  LoadController(DemoSettings settings, ObjectMapper mapper) {
    this.settings = settings;
    this.mapper = mapper;
  }

  public record Start(
      @Min(value = 1, message = "1 이상이어야 합니다.") @Max(value = 100, message = "100 이하여야 합니다.")
          int rate,
      @Min(value = 1, message = "1 이상이어야 합니다.") @Max(value = 30, message = "30 이하여야 합니다.")
          int seconds) {}

  public record View(
      UUID id,
      String state,
      Instant startedAt,
      Integer exitCode,
      String output,
      JsonNode summary) {}

  private static final class Run {
    final UUID id = UUID.randomUUID();
    final Instant startedAt = Instant.now();
    final Path directory;
    final AtomicReference<String> state = new AtomicReference<>("RUNNING");
    volatile Process process;
    volatile Integer exitCode;
    volatile JsonNode summary;
    final StringBuilder output = new StringBuilder();

    Run(Path work) {
      directory = work.resolve("ui-" + id);
    }

    synchronized void append(String line) {
      output.append(line).append('\n');
      if (output.length() > 32_768) output.delete(0, output.length() - 32_768);
    }

    synchronized View view() {
      return new View(id, state.get(), startedAt, exitCode, output.toString(), summary);
    }
  }

  @GetMapping("/demo/load-runs")
  synchronized List<View> list() {
    return runs.values().stream()
        .map(Run::view)
        .sorted(Comparator.comparing(View::startedAt).reversed())
        .toList();
  }

  @PostMapping("/demo/load-runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  synchronized View start(@Valid @RequestBody Start start) {
    if (closing)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "제어 서버를 종료하고 있습니다.");
    if (!settings.loadEnabled())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "부하 실행이 활성화되지 않았습니다.");
    if (!settings.loadProject().matches("commerce-settle-ui-load(?:-[a-z0-9-]{1,32})?")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "전용 commerce-settle-ui-load 프로젝트만 허용합니다.");
    }
    if (runs.values().stream()
        .anyMatch(r -> r.state.get().equals("RUNNING") || r.state.get().equals("STOP_REQUESTED"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 실행 중인 부하가 있습니다.");
    }
    Path root = settings.repositoryRoot().toAbsolutePath().normalize();
    Path work = root.resolve("tests/load/work/demo-ui").resolve(settings.loadProject());
    if (!Files.isRegularFile(root.resolve("tests/load/payment_load.py"))
        || !Files.isRegularFile(work.resolve("operator.credential"))) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "동일 LOAD_PROJECT·LOAD_WORK_DIR로 전용 스택을 먼저 준비하세요.");
    }
    if (runs.size() >= 20)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "실행 기록 상한입니다. 결과를 보존하고 제어 서버를 다시 시작하세요.");
    Run run = new Run(work);
    runs.put(run.id, run);
    executor.submit(() -> execute(run, root, work, start));
    return run.view();
  }

  @PostMapping("/demo/load-runs/{id}/stop")
  synchronized View stop(@PathVariable UUID id) {
    Run run = runs.get(id);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "실행 기록이 없습니다.");
    if (run.state.get().equals("RUNNING") || run.state.get().equals("STOP_REQUESTED")) {
      try {
        Files.createDirectories(run.directory);
        Files.writeString(run.directory.resolve("stop"), "stop additional injection\n");
        run.state.compareAndSet("RUNNING", "STOP_REQUESTED");
      } catch (IOException e) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "중지 요청을 저장하지 못했습니다.");
      }
    }
    return run.view();
  }

  private void execute(Run run, Path root, Path work, Start start) {
    try {
      Files.createDirectories(run.directory);
      ProcessBuilder builder =
          new ProcessBuilder(
              "python3",
              "-B",
              "tests/load/payment_load.py",
              "ramp",
              "--stages",
              start.rate() + ":" + start.seconds(),
              "--mix",
              "card",
              "--run",
              "ui-" + run.id,
              "--interval",
              "2",
              "--drain",
              "30",
              "--stop-file",
              run.directory.resolve("stop").toString());
      builder.directory(root.toFile()).redirectErrorStream(true);
      builder.environment().put("LOAD_PROJECT", settings.loadProject());
      builder.environment().put("LOAD_WORK_DIR", work.toString());
      Process process = builder.start();
      run.process = process;
      Thread output =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var reader = process.inputReader()) {
                      String line;
                      while ((line = reader.readLine()) != null) run.append(line);
                    } catch (IOException e) {
                      run.append("실행 출력 수집이 끝났습니다.");
                    }
                  });
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        terminate(process);
        run.append("실행 상한을 초과했습니다. 이미 접수된 거래의 상태를 확인하세요.");
      }
      output.join(2000);
      run.exitCode = process.isAlive() ? null : process.exitValue();
      Path summary = run.directory.resolve("summary.json");
      if (Files.isRegularFile(summary) && Files.size(summary) <= 1_048_576)
        run.summary = mapper.readTree(summary.toFile());
      run.state.set(
          Integer.valueOf(0).equals(run.exitCode)
              ? Files.exists(run.directory.resolve("stop")) ? "STOPPED" : "COMPLETED"
              : "FAILED");
    } catch (IOException e) {
      run.append("실행 도구·전용 스택·출력 경로를 확인하세요.");
      run.state.set("FAILED");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (run.process != null) terminate(run.process);
      run.state.set("INTERRUPTED");
    }
  }

  static void terminate(Process process) {
    var children = process.descendants().toList();
    children.reversed().forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      process.waitFor(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) process.destroyForcibly();
  }

  @PreDestroy
  void close() {
    synchronized (this) {
      closing = true;
      runs.values().stream()
          .filter(r -> r.state.get().equals("RUNNING"))
          .map(r -> r.id)
          .toList()
          .forEach(this::stop);
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        synchronized (this) {
          runs.values().stream()
              .map(r -> r.process)
              .filter(java.util.Objects::nonNull)
              .forEach(LoadController::terminate);
        }
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
