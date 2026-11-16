package com.autotrade.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a saved broker answer may keep (READ_LIVE recorder, 16 Nov 2026, D2): the order, trade, position, funds-amount and
 * instrument fields a contract test needs; never a credential or the account holder's identity. Applied to the body
 * before it is written, in this order:
 * <ol>
 *   <li>by field name, at any depth (case, {@code _} and {@code -} ignored): credentials (tokens, sid, keys, secrets,
 *       passwords, MPIN, TOTP/OTP), the client's identity (user and client ids, UCC, {@code placed_by}, account ids),
 *       names of people ({@code user_name}, {@code full_name}, ...), contact (email, phone, mobile), PAN, date of birth,
 *       address, and every bank or demat field (any name containing {@code bank}, {@code ifsc}, {@code micr},
 *       {@code demat}, {@code nominee}); a field's whole value is replaced, objects and arrays included;</li>
 *   <li>{@code name}: kept only beside an instrument identifier in the same object (an instrument's or a position's
 *       name: NIFTY), else replaced (a person's name in a profile);</li>
 *   <li>in every remaining string: an email address, a PAN ({@code ABCDE1234F}) and, outside id, number, token and time
 *       fields, an Indian mobile number ({@code [6-9]} and nine digits, optionally +91);</li>
 *   <li>then the session's own values (token, sid, keys) wherever they still appear, in a body that is not JSON too.</li>
 * </ol>
 */
public final class ReadLiveRedaction {

    static final String REDACTED = "<redacted>";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Exact field names (normalised: lower case, no {@code _} or {@code -}) whose values are never saved. */
    static final Set<String> FIELDS = Set.of(
            // credentials
            "accesstoken", "token", "refreshtoken", "publictoken", "enctoken", "auth", "authorization", "sid", "hsservid",
            "rid", "jwt", "session", "sessionid", "sessiontoken", "consumerkey", "apikey", "apisecret", "secret",
            "clientsecret", "password", "pwd", "mpin", "pin", "totp", "otp", "neofinkey", "viewtoken", "tradetoken",
            "requesttoken", "checksum",
            // the client's identity
            "userid", "uid", "usrid", "ucc", "actid", "accountid", "acctid", "clientid", "clientcode", "placedby",
            "loginid", "userkey", "customerid", "kid", "dpid", "boid", "poa",
            // people
            "username", "usershortname", "shortname", "fullname", "firstname", "middlename", "lastname", "displayname",
            "accountname", "holdername", "fathername", "mothername", "spousename", "avatarurl",
            // contact, tax id, birth, address
            "email", "emailid", "mobile", "mobileno", "mobilenumber", "phone", "phoneno", "phonenumber", "pan", "panno",
            "pannumber", "dob", "dateofbirth", "birthdate", "address", "addressline1", "addressline2", "city", "pincode",
            "zipcode", "aadhaar", "aadhar");

    /** Any field whose normalised name contains one of these is never saved (bank and demat details). */
    static final List<String> FIELD_PARTS = List.of("bank", "ifsc", "micr", "demat", "nominee", "email", "mobile",
            "phone", "password", "secret", "aadha");

    /** Beside one of these (normalised), {@code name} names the instrument and is kept. */
    static final Set<String> INSTRUMENT_FIELDS = Set.of("tradingsymbol", "instrumenttoken", "instrumentkey",
            "exchangetoken", "tradingsym", "trdsym", "sym", "tok", "symbol", "isin", "segment", "exseg", "lotsize",
            "expiry", "strikeprice", "strike", "growwsymbol", "underlying", "underlyingsymbol");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])(?:\\+?91[ -]?)?[6-9][0-9]{9}(?![0-9])");
    /**
     * Words of a field name ({@code exchange_order_id}, {@code nOrdNo}, {@code fldQty}) that make its numeric-looking
     * strings ids, counts or times, never a phone number; a word ending in {@code id} or {@code qty} counts too.
     */
    private static final Set<String> NUMERIC_WORDS = Set.of("id", "no", "num", "number", "token", "tok", "time", "tm",
            "ts", "timestamp", "date", "dt", "qty", "quantity", "price", "prc", "amount", "value", "seq", "ref");
    private static final Pattern WORDS = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[_\\-\\s.]+");

    private ReadLiveRedaction() {
    }

    /**
     * The body redacted: JSON by the rules above; a body that is not JSON gets the string patterns and the
     * {@code values} only.
     */
    public static String redact(String body, List<String> values) {
        String out = body == null ? "" : body;
        boolean json = false;
        try {
            JsonNode tree = JSON.readTree(out);
            if (tree != null && (tree.isObject() || tree.isArray())) {
                scrub(tree);
                out = JSON.writeValueAsString(tree);
                json = true;
            }
        } catch (RuntimeException e) {
            // not JSON: the patterns and values below only
        }
        if (!json) {
            out = patterns(out, true);
        }
        if (values != null) {
            for (String v : values) {
                if (v != null && v.length() >= 4) {
                    out = out.replace(v, REDACTED);
                }
            }
        }
        return out;
    }

    static String normalise(String field) {
        return field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    /** Whether a field's whole value is never saved (rule 1). */
    static boolean secretField(String field) {
        String n = normalise(field);
        if (FIELDS.contains(n)) {
            return true;
        }
        for (String part : FIELD_PARTS) {
            if (n.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.properties().forEach(e -> names.add(e.getKey()));
            boolean instrument = names.stream().anyMatch(n -> INSTRUMENT_FIELDS.contains(normalise(n)));
            for (String name : names) {
                JsonNode value = o.get(name);
                if (secretField(name) || (normalise(name).equals("name") && !instrument)) {
                    o.put(name, REDACTED);
                } else if (value != null && value.isString()) {
                    String text = value.asString();
                    String clean = patterns(text, !numericField(name));
                    if (!clean.equals(text)) {
                        o.put(name, clean);
                    }
                } else {
                    scrub(value);
                }
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                if (v != null && v.isString()) {
                    String clean = patterns(v.asString(), true);
                    if (!clean.equals(v.asString())) {
                        a.set(i, a.stringNode(clean));
                    }
                } else {
                    scrub(v);
                }
            }
        }
    }

    static boolean numericField(String field) {
        for (String word : WORDS.split(field)) {
            String w = word.toLowerCase(Locale.ROOT);
            if (NUMERIC_WORDS.contains(w) || w.endsWith("id") || w.endsWith("qty")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A query string ({@code a=1&sId=x}) redacted: the value of every parameter whose name is a secret field, then the
     * patterns and the session's {@code values}.
     */
    public static String redactQuery(String query, List<String> values) {
        if (query == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            out.append(eq >= 0 && secretField(java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8))
                    ? name + "=" + REDACTED : pair);
        }
        return redact(out.toString(), values);
    }

    private static String patterns(String text, boolean phones) {
        String out = EMAIL.matcher(text).replaceAll(REDACTED);
        out = PAN.matcher(out).replaceAll(REDACTED);
        return phones ? MOBILE.matcher(out).replaceAll(REDACTED) : out;
    }
}
