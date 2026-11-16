package com.autotrade.exec.app;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.autotrade.execution.BrokerRules;
import com.autotrade.execution.LiveBrokers;

/**
 * The execution worker's broker configuration (16 Nov 2026, multi-broker gaps A1 and A2): the broker rules file, loaded
 * and checked once at start (a file that breaks a rule stops the worker from starting), and the brokers an account may
 * be switched LIVE on ({@code autotrade.execution.live-brokers}, default ZERODHA), each only when the worker's adapter
 * for it offers a LIVE connection.
 */
@Configuration
class BrokerSetup {

    private static final Logger log = LoggerFactory.getLogger(BrokerSetup.class);

    @Bean
    BrokerRules brokerRules(ExecutionProperties properties) {
        return BrokerRules.load(Path.of(properties.brokerRulesFile()));
    }

    @Bean
    LiveBrokers liveBrokers(ExecutionService service) {
        LiveBrokers live = service.liveBrokers();              // the same instance the loop's routes check
        log.info("LIVE brokers: {}", live.live());
        if (!live.configuredWithoutLiveAdapter().isEmpty()) {
            log.warn("configured LIVE but their adapter offers no LIVE connection (they stay DRY_RUN): {}",
                    live.configuredWithoutLiveAdapter());
        }
        return live;
    }
}
