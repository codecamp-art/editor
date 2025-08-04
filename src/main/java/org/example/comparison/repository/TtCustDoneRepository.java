package org.example.comparison.repository;

import org.example.comparison.domain.TtCustDone;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Repository for TT_CUST_DONE table operations
 */
@Repository
public class TtCustDoneRepository {

    private static final Logger logger = LoggerFactory.getLogger(TtCustDoneRepository.class);
    
    private final JdbcTemplate primaryJdbcTemplate;

    public TtCustDoneRepository(@Qualifier("primaryJdbcTemplate") JdbcTemplate primaryJdbcTemplate) {
        this.primaryJdbcTemplate = primaryJdbcTemplate;
    }

    /**
     * Retrieves all TT_CUST_DONE records for a specific date
     */
    public List<TtCustDone> findByDate(LocalDate date) {
        String sql = """
                SELECT DONE_NO, DONE_PRICE, DONE_QTY, CONTRACT_CODE, 
                       BS_FLAG, CUST_NO, DONE_TIME, STATUS
                FROM TT_CUST_DONE 
                WHERE TRUNC(DONE_TIME) = ?
                ORDER BY DONE_TIME DESC
                """;

        try {
            return primaryJdbcTemplate.query(sql, new TtCustDoneRowMapper(), date);
        } catch (DataAccessException e) {
            logger.error("Error retrieving TT_CUST_DONE records for date: {}", date, e);
            throw new RuntimeException("Failed to retrieve TT_CUST_DONE records", e);
        }
    }

    /**
     * Retrieves all TT_CUST_DONE records
     */
    public List<TtCustDone> findAll() {
        String sql = """
                SELECT DONE_NO, DONE_PRICE, DONE_QTY, CONTRACT_CODE, 
                       BS_FLAG, CUST_NO, DONE_TIME, STATUS
                FROM TT_CUST_DONE 
                ORDER BY DONE_TIME DESC
                """;

        try {
            return primaryJdbcTemplate.query(sql, new TtCustDoneRowMapper());
        } catch (DataAccessException e) {
            logger.error("Error retrieving all TT_CUST_DONE records", e);
            throw new RuntimeException("Failed to retrieve TT_CUST_DONE records", e);
        }
    }

    /**
     * Retrieves TT_CUST_DONE record by DONE_NO
     */
    public TtCustDone findByDoneNo(String doneNo) {
        String sql = """
                SELECT DONE_NO, DONE_PRICE, DONE_QTY, CONTRACT_CODE, 
                       BS_FLAG, CUST_NO, DONE_TIME, STATUS
                FROM TT_CUST_DONE 
                WHERE DONE_NO = ?
                """;

        try {
            return primaryJdbcTemplate.queryForObject(sql, new TtCustDoneRowMapper(), doneNo);
        } catch (DataAccessException e) {
            logger.warn("No TT_CUST_DONE record found for DONE_NO: {}", doneNo);
            return null;
        }
    }

    /**
     * Row mapper for TT_CUST_DONE table
     */
    private static class TtCustDoneRowMapper implements RowMapper<TtCustDone> {
        @Override
        public TtCustDone mapRow(ResultSet rs, int rowNum) throws SQLException {
            TtCustDone record = new TtCustDone();
            record.setDoneNo(rs.getString("DONE_NO"));
            record.setDonePrice(rs.getBigDecimal("DONE_PRICE"));
            record.setDoneQty(rs.getBigDecimal("DONE_QTY"));
            record.setContractCode(rs.getString("CONTRACT_CODE"));
            record.setBsFlag(rs.getString("BS_FLAG"));
            record.setCustNo(rs.getString("CUST_NO"));
            
            // Handle timestamp conversion
            java.sql.Timestamp timestamp = rs.getTimestamp("DONE_TIME");
            if (timestamp != null) {
                record.setDoneTime(timestamp.toLocalDateTime());
            }
            
            record.setStatus(rs.getString("STATUS"));
            return record;
        }
    }
}