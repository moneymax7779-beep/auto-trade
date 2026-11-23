package com.autotrade.trading.activity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser, OS and device type from the user agent and, when the browser sends them, its client hints (Sec-CH-UA,
 * Sec-CH-UA-Platform, Sec-CH-UA-Mobile): Chromium browsers now send a reduced user agent, so the hints win where given.
 * Deliberately small (user activity plan, 23 Nov 2026): the handful of browsers a family uses, major versions only; the
 * raw user agent is stored beside the result, so a better parser can re-read it later.
 */
public final class UserAgents {

    /** What the sign-in page shows: browser and major version, OS and version, device type. */
    public record Device(String browser, String browserVersion, String os, String osVersion, String deviceType) {
        public String label() {
            return browser + (browserVersion == null ? "" : " " + browserVersion) + " / " + os
                    + (osVersion == null ? "" : " " + osVersion);
        }
    }

    private static final Pattern BRAND = Pattern.compile("\"([^\"]+)\"\\s*;\\s*v\\s*=\\s*\"(\\d+)");

    private UserAgents() {
    }

    /**
     * @param ua       the User-Agent header (may be null)
     * @param brands   Sec-CH-UA (e.g. {@code "Chromium";v="131", "Google Chrome";v="131", "Not_A Brand";v="24"}), or null
     * @param platform Sec-CH-UA-Platform (e.g. {@code "macOS"}), or null
     * @param mobile   Sec-CH-UA-Mobile ({@code ?1} / {@code ?0}), or null
     */
    public static Device parse(String ua, String brands, String platform, String mobile) {
        String u = ua == null ? "" : ua;
        String[] browser = browser(u);
        String[] hinted = brands == null ? null : brand(brands);
        if (hinted != null && (browser[0].equals("Other") || browser[0].equals("Chrome") || browser[0].equals("Chromium"))) {
            browser = hinted;                                      // e.g. Brave or a reduced UA: the hint names it
        }
        String[] os = os(u);
        String p = platform == null ? null : platform.replace("\"", "").trim();
        if (p != null && !p.isEmpty() && os[0].equals("Other")) {
            os = new String[] {p, null};
        }
        String type = type(u, os[0], mobile);
        return new Device(browser[0], browser[1], os[0], os[1], type);
    }

    static String[] browser(String u) {
        String[][] rules = {                                       // order matters: the specific before the general
                {"EdgA/", "Edge"}, {"EdgiOS/", "Edge"}, {"Edg/", "Edge"},
                {"OPR/", "Opera"}, {"SamsungBrowser/", "Samsung Internet"},
                {"FxiOS/", "Firefox"}, {"Firefox/", "Firefox"},
                {"CriOS/", "Chrome"}, {"Chromium/", "Chromium"}, {"Chrome/", "Chrome"},
        };
        for (String[] r : rules) {
            String v = version(u, r[0]);
            if (v != null) {
                return new String[] {r[1], v};
            }
        }
        if (u.contains("Safari/")) {
            return new String[] {"Safari", version(u, "Version/")};
        }
        return new String[] {"Other", null};
    }

    /** The most specific brand of Sec-CH-UA: not "Chromium" and not the GREASE "Not A Brand" entry. */
    static String[] brand(String brands) {
        String chromium = null;
        Matcher m = BRAND.matcher(brands);
        while (m.find()) {
            String name = m.group(1), v = m.group(2);
            if (name.toLowerCase(java.util.Locale.ROOT).contains("brand")) {
                continue;
            }
            if (name.equals("Chromium")) {
                chromium = v;
                continue;
            }
            String shortName = name.equals("Google Chrome") ? "Chrome" : name.equals("Microsoft Edge") ? "Edge" : name;
            return new String[] {shortName, v};
        }
        return chromium == null ? null : new String[] {"Chromium", chromium};
    }

    static String[] os(String u) {
        Matcher m;
        if (u.contains("iPad")) {
            m = Pattern.compile("OS (\\d+)[_.](\\d+)").matcher(u);
            return new String[] {"iPadOS", m.find() ? m.group(1) + "." + m.group(2) : null};
        }
        if (u.contains("iPhone") || u.contains("iPod")) {
            m = Pattern.compile("OS (\\d+)[_.](\\d+)").matcher(u);
            return new String[] {"iOS", m.find() ? m.group(1) + "." + m.group(2) : null};
        }
        if ((m = Pattern.compile("Android (\\d+(?:\\.\\d+)?)").matcher(u)).find()) {
            return new String[] {"Android", m.group(1)};
        }
        if (u.contains("Android")) {
            return new String[] {"Android", null};
        }
        if (u.contains("CrOS")) {
            return new String[] {"ChromeOS", null};
        }
        if ((m = Pattern.compile("Windows NT (\\d+\\.\\d+)").matcher(u)).find()) {
            String v = switch (m.group(1)) {
                case "10.0" -> "10/11";                            // browsers send 10.0 for Windows 11 too
                case "6.3" -> "8.1";
                case "6.2" -> "8";
                case "6.1" -> "7";
                default -> m.group(1);
            };
            return new String[] {"Windows", v};
        }
        if (u.contains("Macintosh") || u.contains("Mac OS X")) {
            return new String[] {"macOS", null};                   // browsers freeze it at 10.15.7: no real version
        }
        if (u.contains("Linux")) {
            return new String[] {"Linux", null};
        }
        return new String[] {"Other", null};
    }

    static String type(String u, String os, String mobileHint) {
        if (u.contains("iPad") || u.contains("Tablet")) {         // an iPad's agent says "Mobile/" too
            return "TABLET";
        }
        if ("?1".equals(mobileHint) || u.contains("iPhone") || u.contains("Mobi")) {
            return "MOBILE";
        }
        if (os.equals("Android") && !u.isEmpty()) {               // Android without "Mobile": a tablet
            return "TABLET";
        }
        return switch (os) {
            case "Windows", "macOS", "Linux", "ChromeOS" -> "DESKTOP";
            default -> "OTHER";
        };
    }

    private static String version(String u, String token) {
        int i = u.indexOf(token);
        if (i < 0) {
            return null;
        }
        int s = i + token.length(), e = s;
        while (e < u.length() && Character.isDigit(u.charAt(e))) {
            e++;
        }
        return e > s ? u.substring(s, e) : null;
    }
}
