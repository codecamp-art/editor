package org.example.comparison.service;

import org.example.comparison.domain.*;
import org.example.comparison.repository.FixExecRptRepository;
import org.example.comparison.repository.TtCustDoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Main service for comparing database records with FIX log messages
 */
@Service
public class ComparisonService {

    private static final Logger logger = LoggerFactory.getLogger(ComparisonService.class);
    
    private final TtCustDoneRepository ttCustDoneRepository;
    private final FixExecRptRepository fixExecRptRepository;
    private final SftpService sftpService;
    private final FixMessageParser fixMessageParser;

    public ComparisonService(TtCustDoneRepository ttCustDoneRepository,
                           FixExecRptRepository fixExecRptRepository,
                           SftpService sftpService,
                           FixMessageParser fixMessageParser) {
        this.ttCustDoneRepository = ttCustDoneRepository;
        this.fixExecRptRepository = fixExecRptRepository;
        this.sftpService = sftpService;
        this.fixMessageParser = fixMessageParser;
    }

    /**
     * Performs comprehensive comparison between database and FIX logs
     */
    public ComparisonSummary performComparison(LocalDate comparisonDate) {
        logger.info("Starting comparison for date: {}", comparisonDate);
        
        ComparisonSummary summary = new ComparisonSummary();
        summary.setComparisonDate(comparisonDate);
        
        try {
            // Step 1: Fetch data from TT_CUST_DONE table
            logger.info("Fetching TT_CUST_DONE records...");
            List<TtCustDone> ttCustDoneRecords = ttCustDoneRepository.findByDate(comparisonDate);
            summary.setTotalDbRecords(ttCustDoneRecords.size());
            logger.info("Found {} TT_CUST_DONE records", ttCustDoneRecords.size());

            if (ttCustDoneRecords.isEmpty()) {
                logger.warn("No TT_CUST_DONE records found for date: {}", comparisonDate);
                return summary;
            }

            // Step 2: Fetch related FIX_EXEC_RPT records
            logger.info("Fetching FIX_EXEC_RPT records...");
            List<String> doneNos = ttCustDoneRecords.stream()
                    .map(TtCustDone::getDoneNo)
                    .collect(Collectors.toList());
            
            List<FixExecRpt> fixExecRptRecords = fixExecRptRepository.findByDoneNos(doneNos);
            logger.info("Found {} FIX_EXEC_RPT records", fixExecRptRecords.size());

            // Step 3: Fetch FIX log files from remote server
            logger.info("Fetching FIX log files...");
            Map<String, String> fixLogFiles = sftpService.fetchFixLogFiles(comparisonDate);
            summary.setTotalFixLogFiles(fixLogFiles.size());
            logger.info("Found {} FIX log files", fixLogFiles.size());

            // Step 4: Parse FIX messages from logs
            logger.info("Parsing FIX messages...");
            Map<String, FixMessage> fixMessages = parseAllFixMessages(fixLogFiles);
            summary.setTotalFixMessages(fixMessages.size());
            logger.info("Parsed {} FIX messages", fixMessages.size());

            // Step 5: Create mapping structures
            Map<String, TtCustDone> doneNoToTtCustDone = createTtCustDoneMap(ttCustDoneRecords);
            Map<String, FixExecRpt> execIdToFixExecRpt = createFixExecRptMap(fixExecRptRecords);
            Map<String, List<FixExecRpt>> doneNoToFixExecRpts = createDoneNoToFixExecRptMap(fixExecRptRecords);

            // Step 6: Perform comparisons
            List<ComparisonResult> discrepancies = new ArrayList<>();
            
            // Compare database records with FIX messages
            discrepancies.addAll(compareDbWithFix(doneNoToTtCustDone, doneNoToFixExecRpts, 
                                                 execIdToFixExecRpt, fixMessages));
            
            // Find orphaned FIX messages
            discrepancies.addAll(findOrphanedFixMessages(fixMessages, execIdToFixExecRpt));

            summary.setDiscrepancies(discrepancies);
            summary.setTotalDiscrepancies(discrepancies.size());

            logger.info("Comparison completed. Found {} discrepancies", discrepancies.size());

        } catch (Exception e) {
            logger.error("Error during comparison process", e);
            summary.setErrorMessage(e.getMessage());
        }

        return summary;
    }

    /**
     * Parses FIX messages from all log files
     */
    private Map<String, FixMessage> parseAllFixMessages(Map<String, String> fixLogFiles) {
        Map<String, FixMessage> allMessages = new ConcurrentHashMap<>();
        
        List<CompletableFuture<Void>> futures = fixLogFiles.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() -> {
                    String sessionId = entry.getKey();
                    String logContent = entry.getValue();
                    
                    Map<String, FixMessage> sessionMessages = fixMessageParser.parseFixMessages(logContent, sessionId);
                    allMessages.putAll(sessionMessages);
                }))
                .collect(Collectors.toList());
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        return allMessages;
    }

    /**
     * Creates mapping from DONE_NO to TtCustDone
     */
    private Map<String, TtCustDone> createTtCustDoneMap(List<TtCustDone> records) {
        return records.stream()
                .collect(Collectors.toMap(TtCustDone::getDoneNo, record -> record));
    }

    /**
     * Creates mapping from EXECID to FixExecRpt
     */
    private Map<String, FixExecRpt> createFixExecRptMap(List<FixExecRpt> records) {
        return records.stream()
                .collect(Collectors.toMap(FixExecRpt::getExecId, record -> record, (existing, replacement) -> existing));
    }

    /**
     * Creates mapping from DONE_NO to list of FixExecRpt
     */
    private Map<String, List<FixExecRpt>> createDoneNoToFixExecRptMap(List<FixExecRpt> records) {
        return records.stream()
                .collect(Collectors.groupingBy(FixExecRpt::getDoneNo));
    }

    /**
     * Compares database records with FIX messages
     */
    private List<ComparisonResult> compareDbWithFix(Map<String, TtCustDone> doneNoToTtCustDone,
                                                   Map<String, List<FixExecRpt>> doneNoToFixExecRpts,
                                                   Map<String, FixExecRpt> execIdToFixExecRpt,
                                                   Map<String, FixMessage> fixMessages) {
        List<ComparisonResult> discrepancies = new ArrayList<>();

        for (Map.Entry<String, TtCustDone> entry : doneNoToTtCustDone.entrySet()) {
            String doneNo = entry.getKey();
            TtCustDone ttCustDone = entry.getValue();

            // Check if there are corresponding FIX_EXEC_RPT records
            List<FixExecRpt> relatedFixExecRpts = doneNoToFixExecRpts.get(doneNo);
            
            if (relatedFixExecRpts == null || relatedFixExecRpts.isEmpty()) {
                // No FIX_EXEC_RPT record found
                discrepancies.add(new ComparisonResult(doneNo, null, "EXISTENCE", 
                                                     "EXISTS", "MISSING", 
                                                     ComparisonResult.DiscrepancyType.MISSING_IN_FIX.name(), 
                                                     null));
                continue;
            }

            // For each FIX_EXEC_RPT, check corresponding FIX message
            for (FixExecRpt fixExecRpt : relatedFixExecRpts) {
                String execId = fixExecRpt.getExecId();
                FixMessage fixMessage = fixMessages.get(execId);

                if (fixMessage == null) {
                    // FIX_EXEC_RPT exists but no FIX message found
                    discrepancies.add(new ComparisonResult(doneNo, execId, "FIX_MESSAGE", 
                                                         "EXPECTED", "MISSING", 
                                                         ComparisonResult.DiscrepancyType.MISSING_IN_FIX.name(), 
                                                         fixExecRpt.getSessionId()));
                } else {
                    // Compare field values
                    discrepancies.addAll(compareFieldValues(ttCustDone, fixMessage, fixExecRpt));
                }
            }
        }

        return discrepancies;
    }

    /**
     * Compares individual field values between database and FIX message
     */
    private List<ComparisonResult> compareFieldValues(TtCustDone ttCustDone, FixMessage fixMessage, FixExecRpt fixExecRpt) {
        List<ComparisonResult> discrepancies = new ArrayList<>();

        // Compare DONE_PRICE with tag6 (AvgPrice)
        if (!compareDecimalValues(ttCustDone.getDonePrice(), fixMessage.getAvgPrice())) {
            discrepancies.add(new ComparisonResult(
                    ttCustDone.getDoneNo(), fixMessage.getExecId(), "PRICE",
                    formatDecimal(ttCustDone.getDonePrice()), formatDecimal(fixMessage.getAvgPrice()),
                    ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name(), fixMessage.getSessionId()
            ));
        }

        // Compare DONE_QTY with tag14 (CumQty)
        if (!compareDecimalValues(ttCustDone.getDoneQty(), fixMessage.getCumQty())) {
            discrepancies.add(new ComparisonResult(
                    ttCustDone.getDoneNo(), fixMessage.getExecId(), "QUANTITY",
                    formatDecimal(ttCustDone.getDoneQty()), formatDecimal(fixMessage.getCumQty()),
                    ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name(), fixMessage.getSessionId()
            ));
        }

        // Compare CONTRACT_CODE with tag55 (Symbol)
        if (!compareStringValues(ttCustDone.getContractCode(), fixMessage.getSymbol())) {
            discrepancies.add(new ComparisonResult(
                    ttCustDone.getDoneNo(), fixMessage.getExecId(), "CONTRACT_CODE",
                    ttCustDone.getContractCode(), fixMessage.getSymbol(),
                    ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name(), fixMessage.getSessionId()
            ));
        }

        // Compare BS_FLAG with tag54 (Side)
        if (!compareStringValues(ttCustDone.getBsFlag(), fixMessage.getSide())) {
            discrepancies.add(new ComparisonResult(
                    ttCustDone.getDoneNo(), fixMessage.getExecId(), "BS_FLAG",
                    ttCustDone.getBsFlag(), fixMessage.getSide(),
                    ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name(), fixMessage.getSessionId()
            ));
        }

        // Compare CUST_NO with tag1 (Account)
        if (!compareStringValues(ttCustDone.getCustNo(), fixMessage.getCustNo())) {
            discrepancies.add(new ComparisonResult(
                    ttCustDone.getDoneNo(), fixMessage.getExecId(), "CUST_NO",
                    ttCustDone.getCustNo(), fixMessage.getCustNo(),
                    ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name(), fixMessage.getSessionId()
            ));
        }

        return discrepancies;
    }

    /**
     * Finds FIX messages that don't have corresponding database records
     */
    private List<ComparisonResult> findOrphanedFixMessages(Map<String, FixMessage> fixMessages,
                                                          Map<String, FixExecRpt> execIdToFixExecRpt) {
        List<ComparisonResult> orphaned = new ArrayList<>();

        for (Map.Entry<String, FixMessage> entry : fixMessages.entrySet()) {
            String execId = entry.getKey();
            FixMessage fixMessage = entry.getValue();

            if (!execIdToFixExecRpt.containsKey(execId)) {
                orphaned.add(new ComparisonResult(
                        null, execId, "EXISTENCE",
                        "MISSING", "EXISTS",
                        ComparisonResult.DiscrepancyType.ORPHANED_FIX_RECORD.name(),
                        fixMessage.getSessionId()
                ));
            }
        }

        return orphaned;
    }

    /**
     * Compares decimal values with null safety
     */
    private boolean compareDecimalValues(BigDecimal db, BigDecimal fix) {
        if (db == null && fix == null) return true;
        if (db == null || fix == null) return false;
        return db.compareTo(fix) == 0;
    }

    /**
     * Compares string values with null safety
     */
    private boolean compareStringValues(String db, String fix) {
        return Objects.equals(db, fix);
    }

    /**
     * Formats decimal for display
     */
    private String formatDecimal(BigDecimal value) {
        return value != null ? value.toString() : "NULL";
    }

    /**
     * Summary class for comparison results
     */
    public static class ComparisonSummary {
        private LocalDate comparisonDate;
        private int totalDbRecords;
        private int totalFixLogFiles;
        private int totalFixMessages;
        private int totalDiscrepancies;
        private List<ComparisonResult> discrepancies = new ArrayList<>();
        private String errorMessage;

        // Getters and Setters
        public LocalDate getComparisonDate() { return comparisonDate; }
        public void setComparisonDate(LocalDate comparisonDate) { this.comparisonDate = comparisonDate; }
        public int getTotalDbRecords() { return totalDbRecords; }
        public void setTotalDbRecords(int totalDbRecords) { this.totalDbRecords = totalDbRecords; }
        public int getTotalFixLogFiles() { return totalFixLogFiles; }
        public void setTotalFixLogFiles(int totalFixLogFiles) { this.totalFixLogFiles = totalFixLogFiles; }
        public int getTotalFixMessages() { return totalFixMessages; }
        public void setTotalFixMessages(int totalFixMessages) { this.totalFixMessages = totalFixMessages; }
        public int getTotalDiscrepancies() { return totalDiscrepancies; }
        public void setTotalDiscrepancies(int totalDiscrepancies) { this.totalDiscrepancies = totalDiscrepancies; }
        public List<ComparisonResult> getDiscrepancies() { return discrepancies; }
        public void setDiscrepancies(List<ComparisonResult> discrepancies) { this.discrepancies = discrepancies; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public boolean hasDiscrepancies() {
            return totalDiscrepancies > 0;
        }

        public boolean hasError() {
            return errorMessage != null && !errorMessage.trim().isEmpty();
        }
    }
}