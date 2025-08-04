package org.example.comparison.service;

import org.example.comparison.domain.FixMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FixMessageParser
 */
class FixMessageParserTest {

    private FixMessageParser fixMessageParser;

    @BeforeEach
    void setUp() {
        fixMessageParser = new FixMessageParser();
    }

    @Test
    void testParseFixMessages_ValidLogContent() {
        String logContent = """
                778              34=778\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=100.50\u000138=1.0000\u0001151=0.0000\u000114=1000\u000111=5080106282561837t7k8\u000137=0000013\u000117=EXEC123\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=CUST001\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001
                779              34=779\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=200.75\u000138=1.0000\u0001151=0.0000\u000114=2000\u000111=5080106282561837t7k8\u000137=0000013\u000117=EXEC124\u000154=2\u000155=MSFT\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=CUST002\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001
                """;

        Map<String, FixMessage> messages = fixMessageParser.parseFixMessages(logContent, "SESSION1");

        assertEquals(2, messages.size());
        
        FixMessage message1 = messages.get("EXEC123");
        assertNotNull(message1);
        assertEquals("EXEC123", message1.getExecId());
        assertEquals("CUST001", message1.getCustNo());
        assertEquals(new BigDecimal("100.50"), message1.getAvgPrice());
        assertEquals(new BigDecimal("1000"), message1.getCumQty());
        assertEquals("1", message1.getSide());
        assertEquals("8", message1.getMessageType());
        assertEquals("SESSION1", message1.getSessionId());
        
        FixMessage message2 = messages.get("EXEC124");
        assertNotNull(message2);
        assertEquals("EXEC124", message2.getExecId());
        assertEquals("CUST002", message2.getCustNo());
        assertEquals(new BigDecimal("200.75"), message2.getAvgPrice());
        assertEquals(new BigDecimal("2000"), message2.getCumQty());
        assertEquals("2", message2.getSide());
        assertEquals("MSFT", message2.getSymbol());
        assertEquals("8", message2.getMessageType());
    }

    @Test
    void testParseFixMessages_EmptyContent() {
        Map<String, FixMessage> messages = fixMessageParser.parseFixMessages("", "SESSION1");
        assertTrue(messages.isEmpty());
    }

    @Test
    void testParseFixMessages_NullContent() {
        Map<String, FixMessage> messages = fixMessageParser.parseFixMessages(null, "SESSION1");
        assertTrue(messages.isEmpty());
    }

    @Test
    void testParseFixMessages_NonExecutionReportMessage() {
        String logContent = """
                778              34=778\u000152=20250801-06:28:25.779\u000135=D\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=100.50\u000138=1.0000\u0001151=0.0000\u000114=1000\u000117=EXEC123\u0001110=0\u0001
                """;

        Map<String, FixMessage> messages = fixMessageParser.parseFixMessages(logContent, "SESSION1");
        assertTrue(messages.isEmpty()); // Should ignore non-execution report messages (35=D instead of 35=8)
    }

    @Test
    void testParseFixMessages_InvalidNumericValues() {
        String logContent = """
                778              34=778\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=INVALID\u000138=1.0000\u0001151=0.0000\u000114=INVALID\u000117=EXEC123\u000154=1\u000155=AAPL\u0001110=0\u0001
                """;

        Map<String, FixMessage> messages = fixMessageParser.parseFixMessages(logContent, "SESSION1");

        assertEquals(1, messages.size());
        FixMessage message = messages.get("EXEC123");
        assertNotNull(message);
        assertNull(message.getAvgPrice()); // Should be null due to invalid value
        assertNull(message.getCumQty()); // Should be null due to invalid value
        assertEquals("AAPL", message.getSymbol()); // Valid values should still be parsed
        assertEquals("1", message.getSide());
    }

    @Test
    void testIsCompleteForComparison_CompleteMessage() {
        FixMessage fixMessage = new FixMessage();
        fixMessage.setExecId("EXEC123");
        fixMessage.setCustNo("CUST001");
        fixMessage.setAvgPrice(new BigDecimal("100.50"));
        fixMessage.setCumQty(new BigDecimal("1000"));
        fixMessage.setSide("1");
        fixMessage.setSymbol("AAPL");

        assertTrue(fixMessageParser.isCompleteForComparison(fixMessage));
    }

    @Test
    void testIsCompleteForComparison_IncompleteMessage() {
        FixMessage fixMessage = new FixMessage();
        fixMessage.setExecId("EXEC123");
        fixMessage.setCustNo("CUST001");
        // Missing required fields

        assertFalse(fixMessageParser.isCompleteForComparison(fixMessage));
    }

    @Test
    void testIsCompleteForComparison_NullMessage() {
        assertFalse(fixMessageParser.isCompleteForComparison(null));
    }

    @Test
    void testParseFixMessages_RealLogFormat() {
        // Test with the exact SOH-separated format provided in the requirements
        String logContent = "778              34=778\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000130\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001779              34=779\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000131\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001780              34=780\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000132\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001";

        Map<String, FixMessage> messages = fixMessageParser.parseFixMessages(logContent, "SESSION1");

        // Should parse 3 messages with sequence numbers 778, 779, 780 and different ExecIDs
        assertEquals(3, messages.size());
        
        // Verify each message has correct ExecID extracted from tag 17
        FixMessage message1 = messages.get("00000130");
        assertNotNull(message1, "Should find message with ExecID 00000130");
        assertEquals("00000130", message1.getExecId());
        assertEquals("8", message1.getMessageType()); // Tag 35 = execution report
        assertEquals("SESSION1", message1.getSessionId());
        assertEquals("1", message1.getSide()); // Tag 54
        assertEquals("10000", message1.getCustNo()); // Tag 1
        
        FixMessage message2 = messages.get("00000131");
        assertNotNull(message2, "Should find message with ExecID 00000131");
        assertEquals("00000131", message2.getExecId());
        
        FixMessage message3 = messages.get("00000132");
        assertNotNull(message3, "Should find message with ExecID 00000132");
        assertEquals("00000132", message3.getExecId());
        
        // Verify we have exactly 3 different messages
        assertEquals(3, messages.values().stream().mapToInt(m -> 1).sum());
    }
}