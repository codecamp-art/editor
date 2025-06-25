import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class debug_gbk_path {
    private static final Pattern MAP_KV_PATTERN = Pattern.compile("^(\\s*)([^:]+?):(\\s*)(.*)$");
    private static final Pattern LINE_SPLIT_PATTERN = Pattern.compile("(.*?(?:\\r\\n|\\r|\\n|$))", Pattern.DOTALL);
    
    private static class PathEntry {
        final int indent;
        final String key;
        PathEntry(int indent, String key) {
            this.indent = indent;
            this.key = key;
        }
        public String toString() {
            return "PathEntry(" + indent + ", '" + key + "')";
        }
    }
    
    public static void main(String[] args) throws Exception {
        Charset gbk = Charset.forName("GBK");
        String yaml = "config:\n  port: 8080\n";
        List<String> targetPath = Arrays.asList("config", "port");
        
        System.out.println("Target path: " + targetPath);
        System.out.println("Original YAML:\n" + yaml);
        
        // Simulate the exact parsing logic from updateLines
        byte[] content = yaml.getBytes(gbk);
        String decoded = new String(content, gbk);
        
        // Split keeping trailing empty lines (same as in the actual code)
        List<String> rawLines = new ArrayList<>();
        Matcher m = LINE_SPLIT_PATTERN.matcher(decoded);
        while (m.find()) {
            if (m.group(1).isEmpty()) break; // Last empty match
            rawLines.add(m.group(1));
        }
        
        System.out.println("Raw lines:");
        for (int i = 0; i < rawLines.size(); i++) {
            System.out.println("  " + i + ": '" + rawLines.get(i) + "'");
        }
        
        // Simulate updateLines logic
        Deque<PathEntry> stack = new ArrayDeque<>();
        
        for (int i = 0; i < rawLines.size(); i++) {
            String line = rawLines.get(i);
            String logicalLine = line.replaceAll("(\\r\\n|\\r|\\n)$", "");
            String eol = line.substring(logicalLine.length());
            
            System.out.println("\nProcessing line " + i + ": '" + logicalLine + "'");
            System.out.println("  EOL: '" + eol + "'");
            
            String trimmed = logicalLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                System.out.println("  Skipping (blank/comment)");
                continue;
            }
            
            // Determine indent
            int indent = 0;
            while (indent < logicalLine.length() && logicalLine.charAt(indent) == ' ') indent++;
            System.out.println("  Indent: " + indent);
            
            // Pop stack levels deeper or equal to current indent
            System.out.println("  Stack before pop: " + stack);
            while (!stack.isEmpty() && stack.peekLast().indent >= indent) {
                PathEntry popped = stack.pollLast();
                System.out.println("    Popped: " + popped);
            }
            System.out.println("  Stack after pop: " + stack);
            
            Matcher mMapKv = MAP_KV_PATTERN.matcher(logicalLine);
            if (mMapKv.find()) {
                String indentStr = mMapKv.group(1);
                String key = mMapKv.group(2).trim();
                String afterColonSpaces = mMapKv.group(3);
                String valueAndComment = mMapKv.group(4);
                
                System.out.println("  MAP_KV match:");
                System.out.println("    indentStr: '" + indentStr + "'");
                System.out.println("    key: '" + key + "'");
                System.out.println("    afterColonSpaces: '" + afterColonSpaces + "'");
                System.out.println("    valueAndComment: '" + valueAndComment + "'");
                
                stack.addLast(new PathEntry(indent, key));
                System.out.println("  Stack after push: " + stack);
                
                List<String> candidate = new ArrayList<>();
                for (PathEntry p : stack) candidate.add(p.key);
                System.out.println("  Candidate path: " + candidate);
                System.out.println("  Target path: " + targetPath);
                System.out.println("  Paths equal? " + candidate.equals(targetPath));
                
                if (candidate.equals(targetPath)) {
                    System.out.println("  *** MATCH FOUND! ***");
                    System.out.println("  Value to replace: '" + valueAndComment + "'");
                    return;
                }
            } else {
                System.out.println("  No MAP_KV match");
            }
        }
        
        System.out.println("\n*** PATH NOT FOUND ***");
    }
} 