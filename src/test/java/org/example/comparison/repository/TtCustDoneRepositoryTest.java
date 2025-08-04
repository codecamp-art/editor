package org.example.comparison.repository;

import org.example.comparison.domain.TtCustDone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TtCustDoneRepository
 */
@ExtendWith(MockitoExtension.class)
class TtCustDoneRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResultSet resultSet;

    private TtCustDoneRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TtCustDoneRepository(jdbcTemplate);
    }

    @Test
    void testFindByDate_Success() throws SQLException {
        LocalDate testDate = LocalDate.of(2023, 12, 1);
        
        // Mock data
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(LocalDate.class)))
                .thenReturn(createMockTtCustDoneList());

        // Execute
        List<TtCustDone> result = repository.findByDate(testDate);

        // Verify
        assertNotNull(result);
        assertEquals(2, result.size());
        
        TtCustDone first = result.get(0);
        assertEquals("DONE001", first.getDoneNo());
        assertEquals(new BigDecimal("100.50"), first.getDonePrice());
        assertEquals(new BigDecimal("1000"), first.getDoneQty());
        assertEquals("AAPL", first.getContractCode());
        assertEquals("1", first.getBsFlag());
        assertEquals("CUST001", first.getCustNo());
        
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(testDate));
    }

    @Test
    void testFindByDate_DatabaseException() {
        LocalDate testDate = LocalDate.of(2023, 12, 1);
        
        // Mock exception
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(LocalDate.class)))
                .thenThrow(new DataAccessException("Database error") {});

        // Execute and verify exception
        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> repository.findByDate(testDate));
        
        assertEquals("Failed to retrieve TT_CUST_DONE records", exception.getMessage());
    }

    @Test
    void testFindAll_Success() {
        // Mock data
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(createMockTtCustDoneList());

        // Execute
        List<TtCustDone> result = repository.findAll();

        // Verify
        assertNotNull(result);
        assertEquals(2, result.size());
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class));
    }

    @Test
    void testFindByDoneNo_Success() {
        String doneNo = "DONE001";
        
        // Mock data
        TtCustDone mockRecord = createMockTtCustDone("DONE001");
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(mockRecord);

        // Execute
        TtCustDone result = repository.findByDoneNo(doneNo);

        // Verify
        assertNotNull(result);
        assertEquals("DONE001", result.getDoneNo());
        verify(jdbcTemplate).queryForObject(anyString(), any(RowMapper.class), eq(doneNo));
    }

    @Test
    void testFindByDoneNo_NotFound() {
        String doneNo = "NONEXISTENT";
        
        // Mock exception for not found
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), anyString()))
                .thenThrow(new DataAccessException("No data found") {});

        // Execute
        TtCustDone result = repository.findByDoneNo(doneNo);

        // Verify
        assertNull(result);
    }

    @Test
    void testRowMapper() throws SQLException {
        // Setup mock ResultSet
        when(resultSet.getString("DONE_NO")).thenReturn("DONE001");
        when(resultSet.getBigDecimal("DONE_PRICE")).thenReturn(new BigDecimal("100.50"));
        when(resultSet.getBigDecimal("DONE_QTY")).thenReturn(new BigDecimal("1000"));
        when(resultSet.getString("CONTRACT_CODE")).thenReturn("AAPL");
        when(resultSet.getString("BS_FLAG")).thenReturn("1");
        when(resultSet.getString("CUST_NO")).thenReturn("CUST001");
        when(resultSet.getTimestamp("DONE_TIME")).thenReturn(Timestamp.valueOf(LocalDateTime.now()));
        when(resultSet.getString("STATUS")).thenReturn("ACTIVE");

        // Test the row mapper using reflection to access the private class
        // This would normally be tested through integration tests
        // For unit tests, we verify the mapping logic works through the public methods
        
        // Execute through the public method
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), anyString()))
                .thenAnswer(invocation -> {
                    RowMapper<TtCustDone> mapper = invocation.getArgument(1);
                    return mapper.mapRow(resultSet, 1);
                });

        TtCustDone result = repository.findByDoneNo("DONE001");

        // Verify mapping
        assertNotNull(result);
        assertEquals("DONE001", result.getDoneNo());
        assertEquals(new BigDecimal("100.50"), result.getDonePrice());
        assertEquals(new BigDecimal("1000"), result.getDoneQty());
        assertEquals("AAPL", result.getContractCode());
        assertEquals("1", result.getBsFlag());
        assertEquals("CUST001", result.getCustNo());
        assertEquals("ACTIVE", result.getStatus());
    }

    private List<TtCustDone> createMockTtCustDoneList() {
        return List.of(
                createMockTtCustDone("DONE001"),
                createMockTtCustDone("DONE002")
        );
    }

    private TtCustDone createMockTtCustDone(String doneNo) {
        TtCustDone record = new TtCustDone();
        record.setDoneNo(doneNo);
        record.setDonePrice(new BigDecimal("100.50"));
        record.setDoneQty(new BigDecimal("1000"));
        record.setContractCode("AAPL");
        record.setBsFlag("1");
        record.setCustNo("CUST001");
        record.setDoneTime(LocalDateTime.now());
        record.setStatus("ACTIVE");
        return record;
    }
}