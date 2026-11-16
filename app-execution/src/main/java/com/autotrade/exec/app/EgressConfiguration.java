package com.autotrade.exec.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.autotrade.execution.AccountEgress;

/**
 * The accounts' egress addresses and the static-IP LIVE gate (multi-broker production gaps B1/B2, 16 Nov 2026), from
 * {@code autotrade.execution.egress.accounts} and {@code ...required-brokers}: parsed strictly (a malformed setting stops
 * the service at startup), then each address is checked against this host's interfaces and logged. An address that is
 * not on this host does not stop the service: that account's routes and logins are refused (fail closed), every other
 * account runs.
 */
@Configuration
class EgressConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EgressConfiguration.class);

    @Bean
    AccountEgress accountEgress(@Value("${autotrade.execution.egress.accounts:}") String accounts,
                                @Value("${autotrade.execution.egress.required-brokers:KOTAK_NEO,UPSTOX,GROWW}")
                                String requiredBrokers) {
        AccountEgress egress = AccountEgress.parse(accounts, requiredBrokers);
        log.info("egress: LIVE needs the account's own static IP for {}", egress.requiredBrokers());
        for (String line : egress.check()) {
            if (line.contains("NOT on this host")) {
                log.error("egress: {}", line);
            } else {
                log.info("egress: {}", line);
            }
        }
        return egress;
    }
}
