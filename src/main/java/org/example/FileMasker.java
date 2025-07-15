package org.example;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Utility for masking sensitive values inside different configuration files or plain text files.
 * <p>
 * Supported types:
 * <ul>
 *     <li>ini  – uses {@link IniInPlaceEditor}</li>
 *     <li>yaml – uses {@link YamlInPlaceEditor}</li>
 *     <li>yml  – alias for yaml</li>
 *     <li>other/unknown – treated as plain text; each pattern is considered a regular expression</li>
 * </ul>
 * <p>
 * The implementation is intentionally stateless and thread-safe. All operations are performed entirely
 * in-memory to minimise I/O and guarantee atomic writes.
 */
public final class FileMasker {

    /** Value used to overwrite masked fields. */
    public static final String MASK = "*****";

    private FileMasker() { /* utility class */ }

    /* =====================================================
     * Public API – File variants (will overwrite in-place)
     * ===================================================== */

    /**
     * Masks the specified paths or regexes in the given file.
     *
     * @param file             target file (will be overwritten)
     * @param fileType         file type hint (ini, yaml, yml, or anything else for plain text)
     * @param pathsOrPatterns  list of slash-separated paths (ini/yaml) or regular expressions (plain text)
     * @throws IOException if I/O fails
     */
    public static void mask(File file, String fileType, List<String> pathsOrPatterns) throws IOException {
        Objects.requireNonNull(file, "file must not be null");

        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] modified = mask(in, fileType, pathsOrPatterns);
            Files.write(file.toPath(), modified);
        }
    }

    /* =====================================================
     * Public API – Stream variants (non-destructive, returns bytes)
     * ===================================================== */

    /**
     * Masks the specified paths or regexes in the given input stream.
     * <p>
     * The caller owns the stream and is responsible for closing it. The method reads the entire stream into memory.
     *
     * @param in               input data
     * @param fileType         file type hint (ini, yaml, yml, or anything else for plain text)
     * @param pathsOrPatterns  list of slash-separated paths (ini/yaml) or regular expressions (plain text)
     * @return modified content as byte array
     * @throws IOException if I/O fails
     */
    public static byte[] mask(InputStream in, String fileType, List<String> pathsOrPatterns) throws IOException {
        Objects.requireNonNull(in, "input stream must not be null");
        Objects.requireNonNull(fileType, "fileType must not be null");
        Objects.requireNonNull(pathsOrPatterns, "pathsOrPatterns must not be null");

        String type = fileType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "ini"  -> maskIni(in, pathsOrPatterns);
            case "yaml", "yml" -> maskYaml(in, pathsOrPatterns);
            default      -> maskPlainText(in, pathsOrPatterns);
        };
    }

    /**
     * Masks the input stream content and writes the result to {@code outputFile}. Useful when the caller already
     * has an open stream but still wants the change persisted.
     *
     * @param in           input data (will be fully read; caller is responsible for closing the stream)
     * @param fileType     file type hint
     * @param searchList   paths or regexes depending on {@code fileType}
     * @param outputFile   destination file to write masked data
     * @throws IOException if I/O fails
     */
    public static void mask(InputStream in,
                            String fileType,
                            List<String> searchList,
                            File outputFile) throws IOException {
        byte[] modified = mask(in, fileType, searchList);
        Files.write(outputFile.toPath(), modified);
    }

    /* =====================================================
     * Internal helpers per type
     * ===================================================== */

    private static byte[] maskIni(InputStream in, List<String> paths) throws IOException {
        byte[] data = in.readAllBytes();
        for (String p : paths) {
            data = IniInPlaceEditor.setValue(new ByteArrayInputStream(data), p,
                    MASK, null, null, null, null);
        }
        return data;
    }

    private static byte[] maskYaml(InputStream in, List<String> paths) throws IOException {
        byte[] data = in.readAllBytes();
        for (String p : paths) {
            data = YamlInPlaceEditor.setValue(new ByteArrayInputStream(data), p, MASK);
        }
        return data;
    }

    private static byte[] maskPlainText(InputStream in, List<String> regexes) throws IOException {
        byte[] original = in.readAllBytes();
        String text = new String(original, java.nio.charset.StandardCharsets.UTF_8); // default to UTF-8
        for (String regex : regexes) {
            Pattern pattern = Pattern.compile(regex);
            var matcher = pattern.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String match = matcher.group();
                String replacement;
                int eqIdx = match.indexOf('=');
                if (eqIdx >= 0) {
                    replacement = match.substring(0, eqIdx + 1) + MASK;
                } else {
                    replacement = MASK;
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            text = sb.toString();
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
} 