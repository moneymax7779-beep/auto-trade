package com.autotrade.tools;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import com.autotrade.config.ThresholdConfig;

/**
 * Operator CLI. Commands: sessions, clone, verify, replay, manifests, config-hash.
 * See README.md for usage.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AutotradeToolsApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("config-hash")) {
            System.exit(configHash(args));
        }
        SpringApplication application = new SpringApplication(AutotradeToolsApplication.class);
        System.exit(SpringApplication.exit(application.run(args)));
    }

    /** Needs no database, so it runs before Spring starts. */
    private static int configHash(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: config-hash <file.yaml> [...]");
            return 2;
        }
        int exit = 0;
        for (int i = 1; i < args.length; i++) {
            try {
                ThresholdConfig config = ThresholdConfig.load(Path.of(args[i]));
                System.out.printf("%s  %s  %s  %s%n", config.contentHash(), config.version(), config.status(), args[i]);
            } catch (RuntimeException e) {
                System.err.println("INVALID " + args[i] + ": " + e.getMessage());
                exit = 1;
            }
        }
        return exit;
    }
}
