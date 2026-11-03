package com.commercesettle.democontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class LoadControllerTest {
  @TempDir Path root;

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
