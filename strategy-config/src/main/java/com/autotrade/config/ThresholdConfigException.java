package com.autotrade.config;

import java.util.List;

public class ThresholdConfigException extends RuntimeException {

    private final List<String> problems;

    public ThresholdConfigException(String source, List<String> problems) {
        super(source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public ThresholdConfigException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of(message);
    }

    public List<String> problems() {
        return problems;
    }
}
