package org.example;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, high-level utility to compute the structural diff between two JSON documents.
 * <p>
 * Both documents can be provided as {@link File} instances or arbitrary {@link InputStream}s.
 * The comparison is encoding-aware (UTF-8 with/without BOM, GBK, …) and supports an optional exclusion
 * list whose elements are expressed in the same three-column format as the produced diff rows:
 * {@code [path, valueInLeft, valueInRight]}.
 * <p>
 * Paths are represented in dotted notation with array indices in square brackets – e.g.
 * {@code "root.level[2].name"}.
 * <p>
 * This class is <strong>stateless</strong> and therefore safe for concurrent use.
 */
public final class JsonFileComparator {

    private static final ObjectMapper MAPPER;
    static {
        MAPPER = new ObjectMapper();
        // accept comments, unquoted field names, trailing commas – useful for dev/test fixtures
        MAPPER.configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true);
        MAPPER.configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
        MAPPER.configure(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(), true);
    }

    private JsonFileComparator(){}

    /* -------------------------- Public API -------------------------- */

    public static List<List<String>> diff(File left, File right) throws IOException {
        return diff(left, right, null, null);
    }

    public static List<List<String>> diff(File left, File right, List<List<String>> exclusion, String encoding) throws IOException {
        try(InputStream inL = new FileInputStream(left); InputStream inR = new FileInputStream(right)){
            return diff(inL, inR, exclusion, encoding);
        }
    }

    public static List<List<String>> diff(InputStream left, InputStream right) throws IOException {
        return diff(left, right, null, null);
    }

    /**
     * @param left       left JSON document
     * @param right      right JSON document
     * @param exclusion  optional exclusion list (rows formatted the same as the diff rows)
     * @param encoding   optional encoding hint (e.g. "GBK"); if {@code null} UTF-8 is assumed and UTF-8 BOM is handled.
     * @return diff rows: {@code [path, valueLeft, valueRight]} (value strings use {@code JsonNode#toString()})
     */
    public static List<List<String>> diff(InputStream left, InputStream right, List<List<String>> exclusion, String encoding) throws IOException {
        Charset charset = encoding==null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        JsonNode leftNode = readJson(left, charset);
        JsonNode rightNode = readJson(right, charset);

        List<List<String>> diffs = new ArrayList<>();
        compareNodes("", leftNode, rightNode, diffs);

        if(exclusion!=null && !exclusion.isEmpty()){
            // O(log n) filtering – compute hash for fast removal
            Set<List<String>> exclSet = ConcurrentHashMap.newKeySet(exclusion.size());
            exclSet.addAll(exclusion);
            diffs.removeIf(exclSet::contains);
        }
        return diffs;
    }

    /* -------------------------- Internal helpers -------------------------- */

    private static JsonNode readJson(InputStream in, Charset cs) throws IOException {
        byte[] bytes = in.readAllBytes();
        int offset = 0;
        if(cs.equals(StandardCharsets.UTF_8) && hasUtf8Bom(bytes)){
            offset = 3; // skip UTF-8 BOM
        }
        String json = new String(bytes, offset, bytes.length-offset, cs);
        return MAPPER.readTree(json);
    }

    private static boolean hasUtf8Bom(byte[] bytes){
        return bytes.length>=3 && (bytes[0] & 0xFF)==0xEF && (bytes[1] & 0xFF)==0xBB && (bytes[2] & 0xFF)==0xBF;
    }

    private static void compareNodes(String path, JsonNode left, JsonNode right, List<List<String>> diffs){
        if(Objects.equals(left, right)){
            return; // identical subtree – nothing to record
        }

        if(left==null || left instanceof MissingNode){
            recordDiff(path, null, right, diffs);
            return;
        }
        if(right==null || right instanceof MissingNode){
            recordDiff(path, left, null, diffs);
            return;
        }

        // Types differ or primitive values differ – record and stop recursion
        if(!left.getNodeType().equals(right.getNodeType()) || left.isValueNode() || right.isValueNode()){
            recordDiff(path, left, right, diffs);
            return;
        }

        // Both are objects
        if(left.isObject()){
            Set<String> fieldNames = new HashSet<>();
            left.fieldNames().forEachRemaining(fieldNames::add);
            right.fieldNames().forEachRemaining(fieldNames::add);
            for(String fn : fieldNames){
                String childPath = path.isEmpty() ? fn : path + "." + fn;
                compareNodes(childPath, left.get(fn), right.get(fn), diffs);
            }
            return;
        }

        // Both are arrays
        if(left.isArray() && right.isArray()){
            ArrayNode arrL = (ArrayNode) left;
            ArrayNode arrR = (ArrayNode) right;
            int max = Math.max(arrL.size(), arrR.size());
            for(int i=0;i<max;i++){
                String childPath = path + "[" + i + "]";
                JsonNode ln = i<arrL.size()?arrL.get(i): MissingNode.getInstance();
                JsonNode rn = i<arrR.size()?arrR.get(i): MissingNode.getInstance();
                compareNodes(childPath, ln, rn, diffs);
            }
        }
    }

    private static void recordDiff(String path, JsonNode left, JsonNode right, List<List<String>> diffs){
        String v1 = left==null || left instanceof MissingNode ? null : valueToString(left);
        String v2 = right==null || right instanceof MissingNode ? null : valueToString(right);
        diffs.add(Arrays.asList(path, v1, v2));
    }

    private static String valueToString(JsonNode n){
        if(n == null || n instanceof NullNode || n instanceof MissingNode){
            return null;
        }
        if(n.isValueNode()){
            return n.asText();
        }
        return n.toString();
    }
} 