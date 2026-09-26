package com.autotrade.core.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** The git commit of the working tree, with "-dirty" when there are uncommitted changes. */
public final class CodeVersion {

    private CodeVersion() {
    }

    /** AUTOTRADE_CODE_VERSION when set (container images have no git), else the working tree's commit. */
    public static String current() {
        String fromEnvironment = System.getenv("AUTOTRADE_CODE_VERSION");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        String commit = git("rev-parse", "--short=12", "HEAD");
        if (commit == null) {
            return "unknown";
        }
        String status = git("status", "--porcelain", "--untracked-files=no");
        return status == null || status.isBlank() ? commit : commit + "-dirty";
    }

    private static String git(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return output;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
