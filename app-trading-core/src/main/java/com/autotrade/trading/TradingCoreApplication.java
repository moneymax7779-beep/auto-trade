package com.autotrade.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The trading service. PAPER only: it has no path to a real broker.
 *
 * <pre>
 *   --mode=auto                            always on: UI, plus the live session start-at..stop-after IST on trading days
 *   --mode=live [--session=YYYY-MM-DD]     tail zt-tiger-v2's capture for today, until stop-after
 *   --mode=replay --session=YYYY-MM-DD     run a recorded session through the same live path, then exit
 *   --mode=serve                           UI and history API only, no trading session
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TradingCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingCoreApplication.class, args);
    }
}
