package com.commercesettle.democontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class LoadControllerTest {
  @TempDir Path root;

  @Test
  @DisplayName("중지 파일 쓰기가 늦게 끝나도 종료된 실행을 다시 활성 상태로 바꾸지 않는다")
  void lateStopCannotReopenATerminalRunOrBlockTheNextStart() throws Exception {
    Path script = root.resolve("tests/load/payment_load.py");
    Files.createDirectories(script.getParent());
    Files.writeString(
        script,
        """
        import os,sys,time
        from pathlib import Path
        stop=Path(sys.argv[sys.argv.index('--stop-file')+1])
        os.mkfifo(stop)
        print('ready',flush=True)
        while not (stop.parent/'finish').exists(): time.sleep(.01)
        """);
    Path work = root.resolve("tests/load/work/demo-ui/commerce-settle-ui-load-test");
    Files.createDirectories(work);
    Files.writeString(work.resolve("operator.credential"), "synthetic");
    var controller =
        new LoadController(settings("commerce-settle-ui-load-test"), new ObjectMapper());
    Thread stopper = null;
    Path stop = null;
    try {
      var run = controller.start(new LoadController.Start(1, 1));
      stop = work.resolve("ui-" + run.id()).resolve("stop");
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(controller.list().getFirst().output()).contains("ready"));
      Map<?, ?> runs = (Map<?, ?>) ReflectionTestUtils.getField(controller, "runs");
      Object ownedRun = runs.get(run.id());
      var stopped = new AtomicReference<LoadController.View>();
      stopper = Thread.ofPlatform().start(() -> stopped.set(controller.stop(run.id())));
      Thread stopping = stopper;
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  Arrays.stream(stopping.getStackTrace())
                      .anyMatch(
                          frame ->
                              frame.getClassName().equals("java.nio.file.Files")
                                  && frame.getMethodName().equals("writeString")));
      Files.writeString(stop.resolveSibling("finish"), "finish");
      // Observe the worker without taking the controller monitor held by the blocked stop call.
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  String.valueOf(ReflectionTestUtils.getField(ownedRun, "state"))
                      .equals("STOPPED"));
      try (var input = Files.newInputStream(stop)) {
        input.transferTo(java.io.OutputStream.nullOutputStream());
      }
      stopper.join(3000);

      assertThat(stopper.isAlive()).isFalse();
      assertThat(stopped.get().state()).isEqualTo("STOPPED");
      assertThat(controller.list().getFirst().state()).isEqualTo("STOPPED");
      var next = controller.start(new LoadController.Start(1, 1));
      Path nextDirectory = work.resolve("ui-" + next.id());
      Files.createDirectories(nextDirectory);
      Files.writeString(nextDirectory.resolve("finish"), "finish");
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> controller.list().getFirst().state().equals("STOPPED"));
    } finally {
      if (stopper != null && stopper.isAlive() && stop != null) {
        try (var input = Files.newInputStream(stop)) {
          input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        stopper.join(3000);
      }
      controller.close();
    }
  }

  @Test
  void stopsAdditionalInjectionAndCollectsTheOwnedProcessResult() throws Exception {
    // 합성 CLI로 제어·중지 경계를 확인한다. 실제 Kafka/금융 부하 시험은 아니다.
    Path script = root.resolve("tests/load/payment_load.py");
    Files.createDirectories(script.getParent());
    Files.writeString(
        script,
        """
        import json,sys,time
        from pathlib import Path
        stop=Path(sys.argv[sys.argv.index('--stop-file')+1])
        end=time.monotonic()+8
        while not stop.exists() and time.monotonic()<end: time.sleep(.02)
        (stop.parent/'summary.json').write_text(json.dumps({'stopped':stop.exists()}))
        """);
    Path work = root.resolve("tests/load/work/demo-ui/commerce-settle-ui-load-test");
    Files.createDirectories(work);
    Files.writeString(work.resolve("operator.credential"), "synthetic");
    LoadController controller =
        new LoadController(settings("commerce-settle-ui-load-test"), new ObjectMapper());
    try {
      var run = controller.start(new LoadController.Start(5, 10));
      assertThatThrownBy(() -> controller.start(new LoadController.Start(5, 10)))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(controller.stop(run.id()).state()).isEqualTo("STOP_REQUESTED");
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                var result = controller.list().getFirst();
                assertThat(result.state()).isEqualTo("STOPPED");
                assertThat(result.exitCode()).isZero();
                assertThat(result.summary().path("stopped").asBoolean()).isTrue();
              });
      assertThat(controller.stop(run.id()).state()).isEqualTo("STOPPED");
    } finally {
      controller.close();
    }
  }

  @Test
  void rejectsTheUserDemoProject() {
    LoadController controller =
        new LoadController(settings("commerce-settle-demo"), new ObjectMapper());
    try {
      assertThatThrownBy(() -> controller.start(new LoadController.Start(5, 10)))
          .isInstanceOf(ResponseStatusException.class);
    } finally {
      controller.close();
    }
  }

  private DemoSettings settings(String project) {
    return new DemoSettings(
        root, "http://127.0.0.1:8080", Map.of(), Map.of(), Map.of(), true, project);
  }
}
