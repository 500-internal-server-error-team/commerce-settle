package com.commercesettle.democontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DemoSettings.class)
public class DemoControlApplication {
  public static void main(String[] args) {
    SpringApplication.run(DemoControlApplication.class, args);
  }
}
