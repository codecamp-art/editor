package org.example.comparison.service;

import org.example.comparison.domain.FixMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for parsing FIX messages from log files
 */
@Service
public class FixMessageParser {

    private static final Logger logger = LoggerFactory.getLogger(FixMessageParser.class);
    
    // FIX message pattern to extract sequence number and message content
    // Pattern matches: sequence_number followed by multiple spaces and FIX fields until next sequence number or end
    private static final Pattern FIX_LOG_PATTERN = Pattern.compile(
        "(\\d+)\\s+(.*?)(?=\\d+\\s+|$)", 
        Pattern.DOTALL
    );
    
    // FIX message field separator
    private static final char SOH = '\001'; // Start of Header character
    private static final String SOH_REPLACEMENT = "|"; // For display purposes
    
    // FIX tag constants
    private static final String TAG_ACCOUNT = "1";           // Customer Number
    private static final String TAG_AVG_PRICE = "6";        // Average Price
    private static final String TAG_CUM_QTY = "14";         // Cumulative Quantity
    private static final String TAG_EXEC_ID = "17";         // Execution ID
    private static final String TAG_MSG_TYPE = "35";        // Message Type
    private static final String TAG_SIDE = "54";            // Side (Buy/Sell)
    private static final String TAG_SYMBOL = "55";          // Symbol/Contract Code
    private static final String TAG_TRANSACT_TIME = "60";   // Transaction Time
    
    // Date time formatters for FIX timestamp parsing
    private static final DateTimeFormatter[] TIMESTAMP_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    };

    /**
     * Parses FIX messages from a log file content
     */
    public Map<String, FixMessage> parseFixMessages(String logContent, String sessionId) {
        Map<String, FixMessage> messages = new HashMap<>();
        
        if (logContent == null || logContent.trim().isEmpty()) {
            logger.warn("Empty log content for session: {}", sessionId);
            return messages;
        }

        try {
            // Clean up the log content by removing extra whitespace and line breaks
            String cleanedContent = logContent.replaceAll("\\s+", " ").trim();
            
            Matcher matcher = FIX_LOG_PATTERN.matcher(cleanedContent);
            
            while (matcher.find()) {
                String sequenceNumber = matcher.group(1);
                String messageContent = matcher.group(2);
                
                try {
                    FixMessage fixMessage = parseFixMessage(messageContent, sessionId, sequenceNumber);
                    if (fixMessage != null && fixMessage.getExecId() != null) {
                        messages.put(fixMessage.getExecId(), fixMessage);
                        logger.debug("Parsed FIX message with ExecID: {} (seq: {}) for session: {}", 
                                   fixMessage.getExecId(), sequenceNumber, sessionId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse FIX message (seq: {}) in session {}: {}", 
                              sequenceNumber, sessionId, e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logger.error("Error parsing FIX log content for session: {}", sessionId, e);
        }

        logger.info("Parsed {} FIX messages from session: {}", messages.size(), sessionId);
        return messages;
    }

    /**
     * Parses a single FIX message
     */
    private FixMessage parseFixMessage(String messageContent, String sessionId, String sequenceNumber) {
        if (messageContent == null || messageContent.trim().isEmpty()) {
            return null;
        }

        // Extract tags from the new format where tag=value pairs are concatenated
        Map<String, String> tags = extractTagsFromNewFormat(messageContent);

        // Process execution reports (message type 8) - if tag 35 is missing, assume it's an execution report
        String messageType = tags.get(TAG_MSG_TYPE);
        if (messageType == null) {
            // If no message type specified, assume execution report and set it
            messageType = "8";
            tags.put(TAG_MSG_TYPE, messageType);
            logger.debug("No message type found, assuming execution report (seq: {})", sequenceNumber);
        } else if (!"8".equals(messageType)) {
            logger.debug("Skipping non-execution report message type: {} (seq: {})", messageType, sequenceNumber);
            return null;
        }

        try {
            FixMessage fixMessage = new FixMessage();
            fixMessage.setSessionId(sessionId);
            fixMessage.setRawMessage(messageContent);
            fixMessage.setAllTags(tags);
            fixMessage.setMessageType(messageType);
            
            // Extract required fields
            fixMessage.setExecId(tags.get(TAG_EXEC_ID));
            fixMessage.setCustNo(tags.get(TAG_ACCOUNT));
            fixMessage.setSide(tags.get(TAG_SIDE));
            fixMessage.setSymbol(tags.get(TAG_SYMBOL));
            
            // Parse numeric fields
            parseNumericField(tags.get(TAG_AVG_PRICE), fixMessage::setAvgPrice);
            parseNumericField(tags.get(TAG_CUM_QTY), fixMessage::setCumQty);
            
            // Parse transaction time from tag 60 or tag 52
            String transactTimeStr = tags.get(TAG_TRANSACT_TIME);
            if (transactTimeStr == null || transactTimeStr.trim().isEmpty()) {
                transactTimeStr = tags.get("52"); // SendingTime as fallback
            }
            parseTransactionTime(transactTimeStr, null, fixMessage);
            
            return fixMessage;
            
        } catch (Exception e) {
            logger.warn("Error creating FixMessage from content (seq: {}): {}", sequenceNumber, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts tag-value pairs from FIX message in the new format
     * Format: tag1=value\u0001tag2=value\u0001tag3=value...
     */
    private Map<String, String> extractTagsFromNewFormat(String message) {
        Map<String, String> tags = new HashMap<>();
        
        if (message == null || message.trim().isEmpty()) {
            return tags;
        }
        
        try {
            // First, try to parse using SOH separator (standard FIX format)
            if (message.contains("\u0001")) {
                // Split by SOH character and parse each tag=value pair
                String[] fields = message.split("\u0001");
                for (String field : fields) {
                    if (field.contains("=")) {
                        String[] tagValue = field.split("=", 2);
                        if (tagValue.length == 2) {
                            String tag = tagValue[0].trim();
                            String value = tagValue[1].trim();
                            if (!tag.isEmpty()) {
                                tags.put(tag, value);
                            }
                        }
                    }
                }
                
                logger.debug("Extracted {} tags from SOH-separated message", tags.size());
                if (logger.isTraceEnabled()) {
                    tags.forEach((k, v) -> logger.trace("Tag {}: '{}'", k, v));
                }
                
            } else {
                // Fallback to regex parsing for concatenated format
                Pattern tagPattern = Pattern.compile("(\\d+)=([^=]*?)(?=\\d+=|$)");
                Matcher matcher = tagPattern.matcher(message);
                
                while (matcher.find()) {
                    String tag = matcher.group(1);
                    String value = matcher.group(2);
                    
                    if (tag != null && value != null) {
                        String cleanValue = value.trim();
                        if (cleanValue.isEmpty() && value.length() > 0) {
                            cleanValue = value; // Keep original if trimming resulted in empty
                        }
                        tags.put(tag.trim(), cleanValue);
                    }
                }
                
                logger.debug("Extracted {} tags from concatenated message", tags.size());
            }
            
        } catch (Exception e) {
            logger.warn("Error extracting tags from message: {}", e.getMessage());
            
            // Fallback to the old method
            return extractTagsOldFormat(message);
        }
        
        return tags;
    }
    
    /**
     * Fallback method for extracting tags using pipe separator or other delimiters
     */
    private Map<String, String> extractTagsOldFormat(String message) {
        Map<String, String> tags = new HashMap<>();
        
        // Try different separators in order of preference
        String[] separators = {"|", "\u0001", ";", "&"};
        
        for (String separator : separators) {
            if (message.contains(separator)) {
                String[] fields = message.split(Pattern.quote(separator));
                for (String field : fields) {
                    if (field.contains("=")) {
                        String[] tagValue = field.split("=", 2);
                        if (tagValue.length == 2) {
                            String tag = tagValue[0].trim();
                            String value = tagValue[1].trim();
                            if (!tag.isEmpty()) {
                                tags.put(tag, value);
                            }
                        }
                    }
                }
                if (!tags.isEmpty()) {
                    logger.debug("Extracted {} tags using separator '{}'", tags.size(), separator);
                    break; // Stop if we found tags with this separator
                }
            }
        }
        
        return tags;
    }

    /**
     * Parses numeric field value
     */
    private void parseNumericField(String value, java.util.function.Consumer<BigDecimal> setter) {
        if (value != null && !value.trim().isEmpty()) {
            try {
                setter.accept(new BigDecimal(value.trim()));
            } catch (NumberFormatException e) {
                logger.warn("Invalid numeric value: {}", value);
            }
        }
    }

    /**
     * Parses transaction time from FIX message or log timestamp
     */
    private void parseTransactionTime(String fixTimestamp, String logTimestamp, FixMessage fixMessage) {
        LocalDateTime transactTime = null;
        
        // Try to parse FIX timestamp first
        if (fixTimestamp != null && !fixTimestamp.trim().isEmpty()) {
            transactTime = parseTimestamp(fixTimestamp.trim());
        }
        
        // Fallback to log timestamp
        if (transactTime == null && logTimestamp != null) {
            transactTime = parseTimestamp(logTimestamp);
        }
        
        fixMessage.setTransactTime(transactTime);
    }

    /**
     * Parses timestamp using multiple formatters
     */
    private LocalDateTime parseTimestamp(String timestamp) {
        for (DateTimeFormatter formatter : TIMESTAMP_FORMATTERS) {
            try {
                return LocalDateTime.parse(timestamp, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        
        logger.warn("Unable to parse timestamp: {}", timestamp);
        return null;
    }

    /**
     * Validates if a FIX message is complete for comparison
     */
    public boolean isCompleteForComparison(FixMessage fixMessage) {
        return fixMessage != null &&
               fixMessage.getExecId() != null &&
               fixMessage.getCustNo() != null &&
               fixMessage.getAvgPrice() != null &&
               fixMessage.getCumQty() != null &&
               fixMessage.getSide() != null &&
               fixMessage.getSymbol() != null;
    }
}