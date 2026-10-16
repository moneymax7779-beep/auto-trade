package com.autotrade.exec.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The execution workers (docs/LIVE-TRADING-PLAN.md section 1): a separate process from trading-core. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ExecutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutionApplication.class, args);
    }
}
