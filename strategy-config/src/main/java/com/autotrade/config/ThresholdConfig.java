package com.autotrade.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A versioned strategy threshold file. The content hash is computed over a canonical JSON form
 * (keys sorted, comments and formatting ignored), so two files with the same values have the same
 * hash and any value change produces a new one.
 */
public final class ThresholdConfig {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String sourceName;
    private final Map<String, Object> tree;
    private final String contentHash;

    private ThresholdConfig(String sourceName, Map<String, Object> tree, String contentHash) {
        this.sourceName = sourceName;
        this.tree = tree;
        this.contentHash = contentHash;
    }

    public static ThresholdConfig load(Path path) {
        String yaml;
        try {
            yaml = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ThresholdConfigException("cannot read " + path, e);
        }
        ThresholdConfig config = parse(path.getFileName().toString(), yaml);
        List<String> problems = ThresholdConfigValidator.validate(config, path.getFileName().toString());
        if (!problems.isEmpty()) {
            throw new ThresholdConfigException(path.toString(), problems);
        }
        return config;
    }

    /** Parses without file-name checks; {@link #load(Path)} is the normal entry point. */
    public static ThresholdConfig parse(String sourceName, String yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (JacksonException e) {
            throw new ThresholdConfigException(sourceName + ": invalid YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new ThresholdConfigException(sourceName, List.of("top level must be a mapping"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> canonical = (Map<String, Object>) canonicalise(root);
        String hash = "sha256:" + sha256(JSON.writeValueAsString(canonical));
        return new ThresholdConfig(sourceName, Collections.unmodifiableMap(canonical), hash);
    }

    public String sourceName() {
        return sourceName;
    }

    public String contentHash() {
        return contentHash;
    }

    public String version() {
        return getString("version");
    }

    public String strategy() {
        return getString("strategy");
    }

    public ThresholdStatus status() {
        return ThresholdStatus.valueOf(getString("status"));
    }

    /** The whole file as sorted, unmodifiable maps and lists. */
    public Map<String, Object> tree() {
        return tree;
    }

    public boolean has(String dottedPath) {
        return lookup(dottedPath) != null;
    }

    public Object get(String dottedPath) {
        Object value = lookup(dottedPath);
        if (value == null) {
            throw new ThresholdConfigException(sourceName, List.of("missing key: " + dottedPath));
        }
        return value;
    }

    public double getDouble(String dottedPath) {
        return asNumber(dottedPath, get(dottedPath)).doubleValue();
    }

    public int getInt(String dottedPath) {
        Number number = asNumber(dottedPath, get(dottedPath));
        if (number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new ThresholdConfigException(sourceName, List.of(dottedPath + " is not an integer"));
        }
        return number.intValue();
    }

    public boolean getBoolean(String dottedPath) {
        if (get(dottedPath) instanceof Boolean b) {
            return b;
        }
        throw new ThresholdConfigException(sourceName, List.of(dottedPath + " is not a boolean"));
    }

    public String getString(String dottedPath) {
        if (get(dottedPath) instanceof String s) {
            return s;
        }
        throw new ThresholdConfigException(sourceName, List.of(dottedPath + " is not a string"));
    }

    public List<Double> getDoubleList(String dottedPath) {
        if (!(get(dottedPath) instanceof List<?> list)) {
            throw new ThresholdConfigException(sourceName, List.of(dottedPath + " is not a list"));
        }
        List<Double> values = new ArrayList<>(list.size());
        for (Object item : list) {
            values.add(asNumber(dottedPath, item).doubleValue());
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String dottedPath) {
        if (get(dottedPath) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ThresholdConfigException(sourceName, List.of(dottedPath + " is not a mapping"));
    }

    /** Numeric values of a mapping, in key order. Fails on non-numeric entries. */
    public Map<String, Double> getNumberMap(String dottedPath) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getMap(dottedPath).entrySet()) {
            values.put(entry.getKey(), asNumber(dottedPath + "." + entry.getKey(), entry.getValue()).doubleValue());
        }
        return Collections.unmodifiableMap(values);
    }

    private Object lookup(String dottedPath) {
        Object current = tree;
        for (String key : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private Number asNumber(String dottedPath, Object value) {
        if (value instanceof Number number) {
            return number;
        }
        throw new ThresholdConfigException(sourceName, List.of(dottedPath + " is not a number"));
    }

    private static Object canonicalise(JsonNode node) {
        return switch (node.getNodeType()) {
            case OBJECT -> {
                TreeMap<String, Object> map = new TreeMap<>();
                for (Map.Entry<String, JsonNode> entry : node.properties()) {
                    map.put(entry.getKey(), canonicalise(entry.getValue()));
                }
                yield Collections.unmodifiableMap(map);
            }
            case ARRAY -> {
                List<Object> list = new ArrayList<>(node.size());
                for (JsonNode item : node) {
                    list.add(canonicalise(item));
                }
                yield Collections.unmodifiableList(list);
            }
            case NUMBER -> node.isIntegralNumber() ? (Object) node.longValue() : (Object) node.doubleValue();
            case BOOLEAN -> node.booleanValue();
            case STRING -> node.stringValue();
            case NULL -> throw new ThresholdConfigException("config", List.of("null values are not allowed"));
            default -> throw new ThresholdConfigException("config", List.of("unsupported YAML value: " + node.getNodeType()));
        };
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
