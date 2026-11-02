package com.policyscript.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict loader for the documented JSON object with roles/resource string arrays. */
public final class ConfigLoader {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private ConfigLoader() {}

    public static PolicyConfig load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Configuration file does not exist: " + path);
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static PolicyConfig loadResource(String resource) throws IOException {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Bundled configuration resource not found: " + resource);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static PolicyConfig parse(String json) throws IOException {
        if (json == null || json.isBlank()) throw new IOException("Configuration JSON is empty.");
        Cursor c = new Cursor(json);
        c.whitespace(); c.expect('{', "Expected '{' at the start of the configuration object.");
        Set<String> roles = null, resources = null;
        c.whitespace();
        if (c.take('}')) throw new IOException("Configuration must define both 'roles' and 'resources' arrays.");
        while (true) {
            c.whitespace(); String key = c.string();
            c.whitespace(); c.expect(':', "Expected ':' after configuration key '" + key + "'.");
            c.whitespace(); Set<String> values = parseStringArray(c, key);
            if (key.equals("roles")) {
                if (roles != null) throw new IOException("Configuration contains duplicate 'roles' fields.");
                roles = values;
            } else if (key.equals("resources")) {
                if (resources != null) throw new IOException("Configuration contains duplicate 'resources' fields.");
                resources = values;
            } else {
                throw new IOException("Unsupported configuration field '" + key + "'. Expected only 'roles' and 'resources'.");
            }
            c.whitespace();
            if (c.take('}')) break;
            c.expect(',', "Expected ',' or '}' after configuration field '" + key + "'.");
            c.whitespace();
            if (c.current() == '}') throw new IOException("Trailing commas are not valid in JSON.");
        }
        c.whitespace();
        if (!c.atEnd()) throw new IOException("Unexpected characters after the configuration JSON object.");
        if (roles == null || resources == null) throw new IOException("Configuration must define both 'roles' and 'resources' JSON arrays.");
        if (roles.isEmpty()) throw new IOException("At least one role must be configured.");
        if (resources.isEmpty()) throw new IOException("At least one resource must be configured.");
        if (resources.contains("lab")) {
            throw new IOException("Resource identifier 'lab' is retired in this project to avoid duplicate laboratory choices. Use the canonical name 'computer_lab' in the configuration and policy files.");
        }
        return new PolicyConfig(roles, resources);
    }

    private static Set<String> parseStringArray(Cursor c, String field) throws IOException {
        if (!field.equals("roles") && !field.equals("resources")) throw new IOException("Unsupported configuration field '" + field + "'.");
        c.expect('[', "Expected an array for configuration field '" + field + "'.");
        Set<String> values = new LinkedHashSet<>(); c.whitespace();
        if (c.take(']')) return values;
        while (true) {
            c.whitespace(); String value = c.string();
            if (!IDENTIFIER.matcher(value).matches()) throw new IOException("Invalid identifier '" + value + "' in '" + field + "'. Use letters, digits and underscores, starting with a letter or underscore.");
            if (isReserved(value)) throw new IOException("Reserved PolicyScript keyword '" + value + "' cannot be a configured " + singular(field) + " name.");
            if (!values.add(value)) throw new IOException("Duplicate identifier '" + value + "' in '" + field + "'.");
            c.whitespace();
            if (c.take(']')) return values;
            c.expect(',', "Expected ',' or ']' in '" + field + "' array.");
            c.whitespace();
            if (c.current() == ']') throw new IOException("Trailing commas are not valid in the '" + field + "' array.");
        }
    }

    private static String singular(String field) { return field.equals("roles") ? "role" : "resource"; }
    private static boolean isReserved(String value) {
        return Set.of("ALLOW", "DENY", "TO", "ACCESS", "WHEN", "BETWEEN", "AND", "OR", "PRIORITY", "TIME", "DAY")
                .contains(value.toUpperCase(Locale.ROOT));
    }

    private static final class Cursor {
        private final String text; private int index;
        private Cursor(String text) { this.text = text; }
        private int current() { return index < text.length() ? text.charAt(index) : -1; }
        private boolean atEnd() { return index >= text.length(); }
        private void whitespace() { while (!atEnd() && Character.isWhitespace(text.charAt(index))) index++; }
        private boolean take(char c) { if (!atEnd() && text.charAt(index) == c) { index++; return true; } return false; }
        private void expect(char c, String message) throws IOException { if (!take(c)) throw new IOException(message); }
        private String string() throws IOException {
            if (!take('"')) throw new IOException("Expected a quoted JSON string at character " + index + ".");
            StringBuilder out = new StringBuilder();
            while (!atEnd()) {
                char ch = text.charAt(index++);
                if (ch == '"') return out.toString();
                if (ch == '\\') {
                    if (atEnd()) throw new IOException("Incomplete escape in JSON string.");
                    char escape = text.charAt(index++);
                    switch (escape) {
                        case '"' -> out.append('"'); case '\\' -> out.append('\\'); case '/' -> out.append('/');
                        case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) throw new IOException("Incomplete Unicode escape in JSON string.");
                            String hex = text.substring(index, index + 4);
                            try { out.append((char) Integer.parseInt(hex, 16)); }
                            catch (NumberFormatException ex) { throw new IOException("Invalid Unicode escape in JSON string."); }
                            index += 4;
                        }
                        default -> throw new IOException("Invalid JSON string escape '\\" + escape + "'.");
                    }
                } else {
                    if (ch < 0x20) throw new IOException("Unescaped control character in JSON string.");
                    out.append(ch);
                }
            }
            throw new IOException("Unterminated JSON string.");
        }
    }
}
