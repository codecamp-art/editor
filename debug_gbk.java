import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class debug_gbk {
    private static final Pattern MAP_KV_PATTERN = Pattern.compile("^(\\s*)([^:]+?):(\\s*)(.*)$");
    
    public static void main(String[] args) throws Exception {
        Charset gbk = Charset.forName("GBK");
        String yaml = "config:\n  port: 8080\n";
        
        System.out.println("Original YAML:");
        System.out.println(yaml);
        System.out.println("Bytes in GBK: " + java.util.Arrays.toString(yaml.getBytes(gbk)));
        
        // Simulate what the editor does
        byte[] content = yaml.getBytes(gbk);
        String decoded = new String(content, gbk);
        System.out.println("\nDecoded from GBK:");
        System.out.println(decoded);
        System.out.println("Are they equal? " + yaml.equals(decoded));
        
        // Test line parsing
        String[] lines = decoded.split("\n");
        System.out.println("\nLine parsing:");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            System.out.println("Line " + i + ": '" + line + "'");
            
            Matcher m = MAP_KV_PATTERN.matcher(line);
            if (m.find()) {
                System.out.println("  Matched MAP_KV:");
                System.out.println("  - Indent: '" + m.group(1) + "'");
                System.out.println("  - Key: '" + m.group(2) + "'");
                System.out.println("  - After colon: '" + m.group(3) + "'");
                System.out.println("  - Value: '" + m.group(4) + "'");
            } else {
                System.out.println("  No MAP_KV match");
            }
        }
    }
} 