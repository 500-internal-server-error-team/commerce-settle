package com.commercesettle.democontrol;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 목적지는 실행자가 설정한다. 브라우저 요청으로 URL·자격·프로세스 명령을 받지 않는다. */
@ConfigurationProperties("demo")
public record DemoSettings(
    Path repositoryRoot,
    String gateway,
    Map<String, String> services,
    Map<String, String> operatorCredentials,
    Map<String, String> monitoringLinks,
    boolean loadEnabled,
    String loadProject) {}
