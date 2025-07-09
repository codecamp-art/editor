package org.example;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for mapping environment references between different states.
 * Handles mappings between reference (ref) and current (cur) environments,
 * including special cases for version-specific mappings.
 */
public final class EnvironmentMapper {

    private EnvironmentMapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Maps environment references based on the provided source and target parameters.
     * 
     * @param source the source environment reference (e.g., "ref.prod", "cur.qa")
     * @param target the target environment reference (e.g., "cur.prod", "ref.qa")
     * @return the mapped environment string according to the defined rules
     */
    public static String mapEnvironment(String source, String target) {
        return mapEnvironment(source, target, null);
    }

    /**
     * Maps environment references based on the provided source and target parameters.
     * 
     * @param source the source environment reference (e.g., "ref.prod", "cur.qa")
     * @param target the target environment reference (e.g., "cur.prod", "ref.qa")
     * @param system the system parameter for version-specific mappings
     * @return the mapped environment string according to the defined rules
     */
    public static String mapEnvironment(String source, String target, String system) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("Source and target parameters cannot be null");
        }

        // Get current date in YYYYMMDD format
        String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // Extract environment names from source and target
        String sourceEnv = extractEnvironmentName(source);
        String targetEnv = extractEnvironmentName(target);

        // Check for version suffix patterns with system parameter
        String sourceVersion = extractVersionSuffix(source);
        String targetVersion = extractVersionSuffix(target);

        // Case: cur/ref.X.Y -> cur/ref.X (with system parameter)
        if (sourceVersion != null && targetVersion == null && system != null) {
            String baseSource = source.substring(0, source.lastIndexOf("." + sourceVersion));
            if (baseSource.equals(target)) {
                return sourceVersion + " -> " + system;
            }
        }

        // Case: cur/ref.X -> cur/ref.X.Y (with system parameter)
        if (sourceVersion == null && targetVersion != null && system != null) {
            String baseTarget = target.substring(0, target.lastIndexOf("." + targetVersion));
            if (source.equals(baseTarget)) {
                return system + " -> " + targetVersion;
            }
        }

        // Case 1: ref.{env} -> cur.{env}
        if (source.startsWith("ref.") && target.startsWith("cur.") && sourceEnv.equals(targetEnv)) {
            return currentDate + " -> T";
        }

        // Case 2: cur.{env} -> ref.{env}
        if (source.startsWith("cur.") && target.startsWith("ref.") && sourceEnv.equals(targetEnv)) {
            return "T -> " + currentDate;
        }

        // Case 3: cur.{env} -> cur.{env} (different environments)
        if (source.startsWith("cur.") && target.startsWith("cur.") && !sourceEnv.equals(targetEnv)) {
            return sourceEnv + " -> " + targetEnv;
        }

        // Case 4: ref.{env} -> cur.{env} (different environments)
        if (source.startsWith("ref.") && target.startsWith("cur.") && !sourceEnv.equals(targetEnv)) {
            return sourceEnv + " -> " + targetEnv;
        }

        // Case 5: cur.{env} -> ref.{env} (different environments)
        if (source.startsWith("cur.") && target.startsWith("ref.") && !sourceEnv.equals(targetEnv)) {
            return sourceEnv + " -> " + targetEnv;
        }

        // Case 6: cur.{env}.v8s -> cur.{env} (version specific - legacy support)
        if (source.endsWith(".v8s") && target.startsWith("cur.")) {
            String baseEnv = source.substring(0, source.lastIndexOf(".v8s"));
            if (baseEnv.equals(target)) {
                return "v8s -> v8t";
            }
        }

        // Case 7: cur.{env} -> cur.{env}.v8s (version specific - legacy support)
        if (source.startsWith("cur.") && target.endsWith(".v8s")) {
            String baseEnv = target.substring(0, target.lastIndexOf(".v8s"));
            if (source.equals(baseEnv)) {
                return "v8t -> v8s";
            }
        }

        // If no matching case is found, return a default mapping
        return "Unknown mapping: " + source + " -> " + target;
    }

    /**
     * Extracts the environment name from a reference string.
     * Examples:
     * - "ref.prod" -> "prod"
     * - "cur.qa" -> "qa"
     * - "cur.prod.v8s" -> "prod"
     * 
     * @param reference the reference string
     * @return the extracted environment name
     */
    private static String extractEnvironmentName(String reference) {
        if (reference == null || reference.isEmpty()) {
            return "";
        }

        // Remove "ref." or "cur." prefix
        String env = reference;
        if (env.startsWith("ref.")) {
            env = env.substring(4);
        } else if (env.startsWith("cur.")) {
            env = env.substring(4);
        }

        // Remove version suffix if present (e.g., ".v8s", ".v2", etc.)
        if (env.contains(".")) {
            env = env.substring(0, env.lastIndexOf("."));
        }

        return env;
    }

    /**
     * Extracts the version suffix from a reference string.
     * Examples:
     * - "ref.prod.v8s" -> "v8s"
     * - "cur.qa.v2" -> "v2"
     * - "ref.prod" -> null
     * 
     * @param reference the reference string
     * @return the version suffix or null if not present
     */
    private static String extractVersionSuffix(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }

        // Remove "ref." or "cur." prefix
        String env = reference;
        if (env.startsWith("ref.")) {
            env = env.substring(4);
        } else if (env.startsWith("cur.")) {
            env = env.substring(4);
        }

        // Check if there's a version suffix
        if (env.contains(".")) {
            return env.substring(env.lastIndexOf(".") + 1);
        }

        return null;
    }
} 