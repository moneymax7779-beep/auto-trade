package com.autotrade.tools;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses {@code command --key value --key=value --flag}. Unknown options are errors. */
public final class ToolArgs {

    private final String command;
    private final Map<String, String> options;

    private ToolArgs(String command, Map<String, String> options) {
        this.command = command;
        this.options = options;
    }

    public static ToolArgs parse(String[] args, Set<String> flags) {
        if (args.length == 0) {
            throw new IllegalArgumentException("missing command");
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            String value;
            int equals = key.indexOf('=');
            if (equals >= 0) {
                value = key.substring(equals + 1);
                key = key.substring(0, equals);
            } else if (flags.contains(key)) {
                value = "true";
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            } else {
                throw new IllegalArgumentException("option --" + key + " needs a value");
            }
            options.put(key, value);
        }
        return new ToolArgs(args[0], options);
    }

    public String command() {
        return command;
    }

    public void allowOnly(Set<String> allowed) {
        for (String key : options.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown option for " + command + ": --" + key);
            }
        }
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(options.getOrDefault(key, "false"));
    }

    public String get(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    public boolean has(String key) {
        return options.containsKey(key);
    }

    public List<String> list(String key, String defaultValue) {
        List<String> values = new ArrayList<>();
        for (String part : get(key, defaultValue).split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    public List<String> upperList(String key, String defaultValue) {
        return list(key, defaultValue).stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
    }

    public List<LocalDate> dates(String key) {
        return list(key, "").stream().map(LocalDate::parse).toList();
    }
}
