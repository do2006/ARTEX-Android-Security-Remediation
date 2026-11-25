package com.artex.mobile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, hash-checked backup manifest. Legacy manifests are deliberately rejected. */
final class BackupManifest {
    static final int FORMAT_VERSION = 2;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final Map<String, String> hashes;

    private BackupManifest(Map<String, String> hashes) {
        this.hashes = hashes;
    }

    static BackupManifest create(Map<String, String> hashes) throws Exception {
        if (hashes == null || hashes.isEmpty()) throw new Exception("Backup has no files to verify");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            addValidated(copy, entry.getKey(), entry.getValue());
        }
        return new BackupManifest(copy);
    }

    static BackupManifest parse(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_MANIFEST_BYTES) {
            throw new Exception("Backup manifest is empty or exceeds the size limit");
        }
        Object root = new JsonReader(new String(bytes, StandardCharsets.UTF_8)).read();
        if (!(root instanceof Map)) throw new Exception("Invalid backup manifest format");
        Map<?, ?> object = (Map<?, ?>) root;
        Object format = object.get("format");
        if (!(format instanceof Long) || ((Long) format).longValue() != FORMAT_VERSION) {
            throw new Exception("Unsupported backup manifest version");
        }
        Object files = object.get("files");
        if (!(files instanceof List) || ((List<?>) files).isEmpty()) {
            throw new Exception("Backup manifest has no file integrity data");
        }
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        for (Object item : (List<?>) files) {
            if (!(item instanceof Map)) throw new Exception("Invalid backup manifest file entry");
            Map<?, ?> file = (Map<?, ?>) item;
            Object path = file.get("path");
            Object sha256 = file.get("sha256");
            if (!(path instanceof String) || !(sha256 instanceof String)) {
                throw new Exception("Backup manifest entry is missing a path or hash");
            }
            addValidated(hashes, (String) path, (String) sha256);
        }
        return new BackupManifest(hashes);
    }

    private static void addValidated(Map<String, String> hashes, String path, String sha256)
            throws Exception {
        if (!RestorePolicy.isRestorableFile(path) || !SecurityPolicy.isSha256(sha256)) {
            throw new Exception("Backup manifest contains a disallowed file entry");
        }
        if (hashes.put(path, sha256.toLowerCase(java.util.Locale.US)) != null) {
            throw new Exception("Backup manifest contains a duplicate file entry");
        }
    }

    Map<String, String> copyHashes() { return new LinkedHashMap<>(hashes); }

    byte[] toJson(String version) {
        StringBuilder out = new StringBuilder("{\"format\":2,\"app\":\"ARTEX Android\",\"version\":");
        appendString(out, version == null ? "unknown" : version);
        out.append(",\"files\":[");
        boolean first = true;
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"path\":");
            appendString(out, entry.getKey());
            out.append(",\"sha256\":");
            appendString(out, entry.getValue());
            out.append('}');
        }
        out.append("]}");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    /** Small strict JSON reader for the fixed manifest schema; avoids a runtime-only parser dependency. */
    private static final class JsonReader {
        private final String source;
        private int at;

        JsonReader(String source) { this.source = source; }

        Object read() throws Exception {
            Object value = value();
            whitespace();
            if (at != source.length()) throw error("Unexpected trailing content");
            return value;
        }

        private Object value() throws Exception {
            whitespace();
            if (at >= source.length()) throw error("Unexpected end of input");
            char c = source.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw error("Invalid value");
        }

        private Map<String, Object> object() throws Exception {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return map;
            while (true) {
                whitespace();
                String key = string();
                if (map.containsKey(key)) throw error("Duplicate key");
                whitespace(); expect(':');
                map.put(key, value());
                whitespace();
                if (consume('}')) return map;
                expect(',');
            }
        }

        private List<Object> array() throws Exception {
            expect('[');
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            whitespace();
            if (consume(']')) return values;
            while (true) {
                values.add(value());
                whitespace();
                if (consume(']')) return values;
                expect(',');
            }
        }

        private String string() throws Exception {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("Control character in string");
                if (c != '\\') { out.append(c); continue; }
                if (at >= source.length()) throw error("Incomplete escape sequence");
                char escaped = source.charAt(at++);
                switch (escaped) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (at + 4 > source.length()) throw error("Incomplete Unicode escape");
                        try { out.append((char) Integer.parseInt(source.substring(at, at + 4), 16)); }
                        catch (NumberFormatException e) { throw error("Invalid Unicode escape"); }
                        at += 4;
                        break;
                    default: throw error("Invalid string escape");
                }
            }
            throw error("Unterminated string");
        }

        private Long number() throws Exception {
            int start = at;
            if (source.charAt(at) == '-') at++;
            while (at < source.length() && Character.isDigit(source.charAt(at))) at++;
            try { return Long.valueOf(source.substring(start, at)); }
            catch (NumberFormatException e) { throw error("Invalid number"); }
        }

        private void whitespace() {
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        }

        private boolean consume(char wanted) {
            if (at < source.length() && source.charAt(at) == wanted) { at++; return true; }
            return false;
        }

        private void expect(char wanted) throws Exception {
            if (!consume(wanted)) throw error("Expected '" + wanted + "'");
        }

        private Exception error(String message) { return new Exception("Backup manifest " + message); }
    }
}
