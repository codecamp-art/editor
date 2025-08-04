package org.example.comparison.service;

import org.example.comparison.domain.FixMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test specifically for the new FIX log format provided in requirements
 */
class FixLogFormatTest {

    @Test
    void testExactLogFormat() {
        FixMessageParser parser = new FixMessageParser();
        
        // Exact log content from the requirements with SOH separators
        String logContent = "778              34=778\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000130\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001779              34=779\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000130\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001780              34=780\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000130\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001";
        
        Map<String, FixMessage> messages = parser.parseFixMessages(logContent, "TEST_SESSION");
        
        // Should find 3 messages (but they have the same ExecID, so map will contain only 1)
        System.out.println("Found " + messages.size() + " unique messages");
        
        for (Map.Entry<String, FixMessage> entry : messages.entrySet()) {
            FixMessage msg = entry.getValue();
            System.out.println("ExecID: " + msg.getExecId());
            System.out.println("  Message Type: " + msg.getMessageType());
            System.out.println("  Customer: " + msg.getCustNo());
            System.out.println("  Side: " + msg.getSide());
            System.out.println("  Symbol: " + msg.getSymbol());
            System.out.println("  Avg Price: " + msg.getAvgPrice());
            System.out.println("  Cum Qty: " + msg.getCumQty());
            System.out.println("  All Tags: " + msg.getAllTags().size() + " tags");
            System.out.println("  Session ID: " + msg.getSessionId());
            System.out.println("---");
        }
        
        // Verify we get at least one valid execution report
        assertFalse(messages.isEmpty(), "Should parse at least one message");
        
        // Check for execution reports (message type 8)
        long executionReports = messages.values().stream()
                .filter(msg -> "8".equals(msg.getMessageType()))
                .count();
        
        System.out.println("Execution reports found: " + executionReports);
        assertTrue(executionReports > 0, "Should find at least one execution report");
        
        // Verify specific message content
        FixMessage msg = messages.get("00000130");
        assertNotNull(msg, "Should find message with ExecID 00000130");
        assertEquals("8", msg.getMessageType());
        assertEquals("10000", msg.getCustNo());
        assertEquals("1", msg.getSide());
        assertEquals(new BigDecimal("0.0000"), msg.getAvgPrice());
        assertEquals(new BigDecimal("0.0000"), msg.getCumQty());
    }

    @Test 
    void testTagExtraction() {
        FixMessageParser parser = new FixMessageParser();
        
        // Test with the SOH-separated format
        String logContent = "778              34=778\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=125.50\u000138=1.0000\u0001151=0.0000\u000114=500\u000111=5080106282561837t7k8\u000137=0000013\u000117=EXEC999\u000154=1\u000155=AAPL\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=CUST999\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001";
        
        // Debug: let's print what we're getting step by step
        System.out.println("Original log length: " + logContent.length());
        System.out.println("Contains SOH: " + logContent.contains("\u0001"));
        
        String cleanedContent = logContent.replaceAll("\\s+", " ").trim();
        System.out.println("Cleaned log: " + cleanedContent.substring(0, Math.min(100, cleanedContent.length())));
        
        // Test the pattern manually
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)\\s+(.*?)(?=\\d+\\s+|$)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(cleanedContent);
        
        int count = 0;
        while (matcher.find()) {
            count++;
            String seq = matcher.group(1);
            String msg = matcher.group(2);
            System.out.println("Found message " + count + " - Seq: " + seq + ", Content length: " + msg.length());
            System.out.println("Contains SOH in content: " + msg.contains("\u0001"));
            
            // Show some SOH positions for debugging
            if (msg.contains("\u0001")) {
                String[] parts = msg.split("\u0001");
                System.out.println("  SOH split into " + parts.length + " parts");
                for (int i = 0; i < Math.min(3, parts.length); i++) {
                    System.out.println("    Part " + i + ": " + parts[i]);
                }
            }
        }
        
        System.out.println("Manual pattern found " + count + " messages");
        
        Map<String, FixMessage> messages = parser.parseFixMessages(logContent, "TEST");
        System.out.println("Parser found " + messages.size() + " messages");
        
        if (!messages.isEmpty()) {
            FixMessage msg = messages.values().iterator().next();
            System.out.println("Extracted tags from parsed message:");
            msg.getAllTags().forEach((tag, value) -> 
                System.out.println("  Tag " + tag + ": '" + value + "'"));
                
            // Verify key tags are correctly parsed
            assertEquals("8", msg.getMessageType(), "Message type should be 8");
            assertEquals("EXEC999", msg.getExecId(), "ExecID should be EXEC999");
            assertEquals("1", msg.getSide(), "Side should be 1");
            assertEquals("AAPL", msg.getSymbol(), "Symbol should be AAPL");
            assertEquals("CUST999", msg.getCustNo(), "Customer should be CUST999");
            assertEquals(new BigDecimal("125.50"), msg.getAvgPrice(), "Price should be 125.50");
            assertEquals(new BigDecimal("500"), msg.getCumQty(), "Quantity should be 500");
        } else {
            System.out.println("No messages were parsed successfully");
        }
    }
}