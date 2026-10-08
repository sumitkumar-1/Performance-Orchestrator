package com.example.perforchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
  exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
)
@EnableScheduling
public class OrchestratorApplication {

  public static void main(final String[] args) {
    SpringApplication.run(OrchestratorApplication.class, args);
  }
}
