package org.example.comparison.service;

import org.example.comparison.domain.*;
import org.example.comparison.repository.FixExecRptRepository;
import org.example.comparison.repository.TtCustDoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ComparisonService
 */
@ExtendWith(MockitoExtension.class)
class ComparisonServiceTest {

    @Mock
    private TtCustDoneRepository ttCustDoneRepository;

    @Mock
    private FixExecRptRepository fixExecRptRepository;

    @Mock
    private SftpService sftpService;

    @Mock
    private FixMessageParser fixMessageParser;

    private ComparisonService comparisonService;

    @BeforeEach
    void setUp() {
        comparisonService = new ComparisonService(
                ttCustDoneRepository, fixExecRptRepository, sftpService, fixMessageParser);
    }

    @Test
    void testPerformComparison_NoDiscrepancies() {
        LocalDate comparisonDate = LocalDate.of(2023, 12, 1);
        
        // Setup test data
        TtCustDone ttCustDone = createTtCustDone("DONE001", "100.50", "1000", "AAPL", "1", "CUST001");
        FixExecRpt fixExecRpt = new FixExecRpt("DONE001", "EXEC001", "SESSION1", "8", "ACTIVE");
        FixMessage fixMessage = createFixMessage("EXEC001", "CUST001", "100.50", "1000", "1", "AAPL", "SESSION1");

        // Mock repository calls
        when(ttCustDoneRepository.findByDate(comparisonDate)).thenReturn(List.of(ttCustDone));
        when(fixExecRptRepository.findByDoneNos(any())).thenReturn(List.of(fixExecRpt));
        when(sftpService.fetchFixLogFiles(comparisonDate)).thenReturn(Map.of("SESSION1", "log content"));
        when(fixMessageParser.parseFixMessages(anyString(), anyString()))
                .thenReturn(Map.of("EXEC001", fixMessage));

        // Execute
        ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);

        // Verify
        assertNotNull(summary);
        assertEquals(comparisonDate, summary.getComparisonDate());
        assertEquals(1, summary.getTotalDbRecords());
        assertEquals(1, summary.getTotalFixLogFiles());
        assertEquals(1, summary.getTotalFixMessages());
        assertEquals(0, summary.getTotalDiscrepancies());
        assertFalse(summary.hasDiscrepancies());
        assertFalse(summary.hasError());
    }

    @Test
    void testPerformComparison_WithDiscrepancies() {
        LocalDate comparisonDate = LocalDate.of(2023, 12, 1);
        
        // Setup test data with mismatched values
        TtCustDone ttCustDone = createTtCustDone("DONE001", "100.50", "1000", "AAPL", "1", "CUST001");
        FixExecRpt fixExecRpt = new FixExecRpt("DONE001", "EXEC001", "SESSION1", "8", "ACTIVE");
        FixMessage fixMessage = createFixMessage("EXEC001", "CUST001", "99.50", "1000", "1", "AAPL", "SESSION1"); // Price mismatch

        // Mock repository calls
        when(ttCustDoneRepository.findByDate(comparisonDate)).thenReturn(List.of(ttCustDone));
        when(fixExecRptRepository.findByDoneNos(any())).thenReturn(List.of(fixExecRpt));
        when(sftpService.fetchFixLogFiles(comparisonDate)).thenReturn(Map.of("SESSION1", "log content"));
        when(fixMessageParser.parseFixMessages(anyString(), anyString()))
                .thenReturn(Map.of("EXEC001", fixMessage));

        // Execute
        ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);

        // Verify
        assertNotNull(summary);
        assertEquals(1, summary.getTotalDiscrepancies());
        assertTrue(summary.hasDiscrepancies());
        
        ComparisonResult discrepancy = summary.getDiscrepancies().get(0);
        assertEquals("DONE001", discrepancy.getDoneNo());
        assertEquals("EXEC001", discrepancy.getExecId());
        assertEquals("PRICE", discrepancy.getField());
        assertEquals("100.50", discrepancy.getDatabaseValue());
        assertEquals("99.50", discrepancy.getFixValue());
        assertEquals(ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name(), discrepancy.getDiscrepancyType());
    }

    @Test
    void testPerformComparison_MissingFixMessage() {
        LocalDate comparisonDate = LocalDate.of(2023, 12, 1);
        
        // Setup test data
        TtCustDone ttCustDone = createTtCustDone("DONE001", "100.50", "1000", "AAPL", "1", "CUST001");
        FixExecRpt fixExecRpt = new FixExecRpt("DONE001", "EXEC001", "SESSION1", "8", "ACTIVE");

        // Mock repository calls
        when(ttCustDoneRepository.findByDate(comparisonDate)).thenReturn(List.of(ttCustDone));
        when(fixExecRptRepository.findByDoneNos(any())).thenReturn(List.of(fixExecRpt));
        when(sftpService.fetchFixLogFiles(comparisonDate)).thenReturn(Map.of("SESSION1", "log content"));
        when(fixMessageParser.parseFixMessages(anyString(), anyString()))
                .thenReturn(Collections.emptyMap()); // No FIX messages

        // Execute
        ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);

        // Verify
        assertNotNull(summary);
        assertEquals(1, summary.getTotalDiscrepancies());
        assertTrue(summary.hasDiscrepancies());
        
        ComparisonResult discrepancy = summary.getDiscrepancies().get(0);
        assertEquals("DONE001", discrepancy.getDoneNo());
        assertEquals("EXEC001", discrepancy.getExecId());
        assertEquals("FIX_MESSAGE", discrepancy.getField());
        assertEquals("EXPECTED", discrepancy.getDatabaseValue());
        assertEquals("MISSING", discrepancy.getFixValue());
        assertEquals(ComparisonResult.DiscrepancyType.MISSING_IN_FIX.name(), discrepancy.getDiscrepancyType());
    }

    @Test
    void testPerformComparison_OrphanedFixMessage() {
        LocalDate comparisonDate = LocalDate.of(2023, 12, 1);
        
        // Setup test data
        FixMessage orphanedFixMessage = createFixMessage("EXEC999", "CUST999", "100.50", "1000", "1", "AAPL", "SESSION1");

        // Mock repository calls
        when(ttCustDoneRepository.findByDate(comparisonDate)).thenReturn(Collections.emptyList());
        when(fixExecRptRepository.findByDoneNos(any())).thenReturn(Collections.emptyList());
        when(sftpService.fetchFixLogFiles(comparisonDate)).thenReturn(Map.of("SESSION1", "log content"));
        when(fixMessageParser.parseFixMessages(anyString(), anyString()))
                .thenReturn(Map.of("EXEC999", orphanedFixMessage));

        // Execute
        ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);

        // Verify
        assertNotNull(summary);
        assertEquals(1, summary.getTotalDiscrepancies());
        assertTrue(summary.hasDiscrepancies());
        
        ComparisonResult discrepancy = summary.getDiscrepancies().get(0);
        assertNull(discrepancy.getDoneNo());
        assertEquals("EXEC999", discrepancy.getExecId());
        assertEquals("EXISTENCE", discrepancy.getField());
        assertEquals("MISSING", discrepancy.getDatabaseValue());
        assertEquals("EXISTS", discrepancy.getFixValue());
        assertEquals(ComparisonResult.DiscrepancyType.ORPHANED_FIX_RECORD.name(), discrepancy.getDiscrepancyType());
    }

    @Test
    void testPerformComparison_NoDbRecords() {
        LocalDate comparisonDate = LocalDate.of(2023, 12, 1);
        
        // Mock repository calls
        when(ttCustDoneRepository.findByDate(comparisonDate)).thenReturn(Collections.emptyList());

        // Execute
        ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);

        // Verify
        assertNotNull(summary);
        assertEquals(0, summary.getTotalDbRecords());
        assertEquals(0, summary.getTotalDiscrepancies());
        assertFalse(summary.hasDiscrepancies());
        
        // Should not call other services if no DB records
        verify(fixExecRptRepository, never()).findByDoneNos(any());
        verify(sftpService, never()).fetchFixLogFiles(any());
        verify(fixMessageParser, never()).parseFixMessages(anyString(), anyString());
    }

    @Test
    void testPerformComparison_ExceptionHandling() {
        LocalDate comparisonDate = LocalDate.of(2023, 12, 1);
        
        // Mock exception
        when(ttCustDoneRepository.findByDate(comparisonDate))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Execute
        ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);

        // Verify
        assertNotNull(summary);
        assertTrue(summary.hasError());
        assertEquals("Database connection failed", summary.getErrorMessage());
    }

    private TtCustDone createTtCustDone(String doneNo, String price, String qty, String contractCode, String bsFlag, String custNo) {
        TtCustDone ttCustDone = new TtCustDone();
        ttCustDone.setDoneNo(doneNo);
        ttCustDone.setDonePrice(new BigDecimal(price));
        ttCustDone.setDoneQty(new BigDecimal(qty));
        ttCustDone.setContractCode(contractCode);
        ttCustDone.setBsFlag(bsFlag);
        ttCustDone.setCustNo(custNo);
        ttCustDone.setDoneTime(LocalDateTime.now());
        ttCustDone.setStatus("ACTIVE");
        return ttCustDone;
    }

    private FixMessage createFixMessage(String execId, String custNo, String avgPrice, String cumQty, String side, String symbol, String sessionId) {
        FixMessage fixMessage = new FixMessage();
        fixMessage.setExecId(execId);
        fixMessage.setCustNo(custNo);
        fixMessage.setAvgPrice(new BigDecimal(avgPrice));
        fixMessage.setCumQty(new BigDecimal(cumQty));
        fixMessage.setSide(side);
        fixMessage.setSymbol(symbol);
        fixMessage.setSessionId(sessionId);
        fixMessage.setMessageType("8");
        fixMessage.setTransactTime(LocalDateTime.now());
        return fixMessage;
    }
}