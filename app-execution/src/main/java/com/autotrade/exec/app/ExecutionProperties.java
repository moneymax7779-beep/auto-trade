package com.autotrade.exec.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code autotrade.execution.*}: the copy group this worker serves, how often it ticks, the costs file, the simulated
 * broker funds a DRY_RUN account is checked against, this worker's id (lease owner) and, for drills only, {@code day}:
 * the session date the worker serves instead of today (a replayed day streamed through the worker; blank = today).
 */
@ConfigurationProperties(prefix = "autotrade.execution")
public record ExecutionProperties(String tenant, String copyGroup, long pollMs, String costsFile, double brokerFunds,
                                  String workerId, String day) {

    public ExecutionProperties {
        tenant = tenant == null || tenant.isBlank() ? "owner" : tenant;
        copyGroup = copyGroup == null || copyGroup.isBlank() ? "LIVE-SET" : copyGroup;
        pollMs = pollMs <= 0 ? 1000 : pollMs;
        costsFile = costsFile == null || costsFile.isBlank() ? "config/costs/india-index-options-costs.v2.yaml" : costsFile;
        brokerFunds = brokerFunds <= 0 ? 1e9 : brokerFunds;
        workerId = workerId == null || workerId.isBlank() ? "exec-" + ProcessHandle.current().pid() : workerId;
        day = day == null || day.isBlank() ? null : java.time.LocalDate.parse(day).toString();
    }
}
