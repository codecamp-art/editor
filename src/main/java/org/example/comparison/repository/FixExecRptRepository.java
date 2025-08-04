package org.example.comparison.repository;

import org.example.comparison.domain.FixExecRpt;
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
 * Repository for FIX_EXEC_RPT table operations
 */
@Repository
public class FixExecRptRepository {

    private static final Logger logger = LoggerFactory.getLogger(FixExecRptRepository.class);
    
    private final JdbcTemplate secondaryJdbcTemplate;

    public FixExecRptRepository(@Qualifier("secondaryJdbcTemplate") JdbcTemplate secondaryJdbcTemplate) {
        this.secondaryJdbcTemplate = secondaryJdbcTemplate;
    }

    /**
     * Retrieves FIX_EXEC_RPT records by DONE_NO
     */
    public List<FixExecRpt> findByDoneNo(String doneNo) {
        String sql = """
                SELECT DONE_NO, EXECID, SESSION_ID, MESSAGE_TYPE, STATUS
                FROM FIX_EXEC_RPT 
                WHERE DONE_NO = ?
                ORDER BY EXECID
                """;

        try {
            return secondaryJdbcTemplate.query(sql, new FixExecRptRowMapper(), doneNo);
        } catch (DataAccessException e) {
            logger.error("Error retrieving FIX_EXEC_RPT records for DONE_NO: {}", doneNo, e);
            throw new RuntimeException("Failed to retrieve FIX_EXEC_RPT records", e);
        }
    }

    /**
     * Retrieves FIX_EXEC_RPT records by multiple DONE_NO values
     */
    public List<FixExecRpt> findByDoneNos(List<String> doneNos) {
        if (doneNos == null || doneNos.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", doneNos.stream().map(d -> "?").toArray(String[]::new));
        String sql = String.format("""
                SELECT DONE_NO, EXECID, SESSION_ID, MESSAGE_TYPE, STATUS
                FROM FIX_EXEC_RPT 
                WHERE DONE_NO IN (%s)
                ORDER BY DONE_NO, EXECID
                """, placeholders);

        try {
            return secondaryJdbcTemplate.query(sql, new FixExecRptRowMapper(), doneNos.toArray());
        } catch (DataAccessException e) {
            logger.error("Error retrieving FIX_EXEC_RPT records for DONE_NO list", e);
            throw new RuntimeException("Failed to retrieve FIX_EXEC_RPT records", e);
        }
    }

    /**
     * Retrieves FIX_EXEC_RPT records for a specific date
     */
    public List<FixExecRpt> findByDate(LocalDate date) {
        String sql = """
                SELECT DONE_NO, EXECID, SESSION_ID, MESSAGE_TYPE, STATUS
                FROM FIX_EXEC_RPT 
                WHERE TRUNC(CREATED_DATE) = ?
                ORDER BY DONE_NO, EXECID
                """;

        try {
            return secondaryJdbcTemplate.query(sql, new FixExecRptRowMapper(), date);
        } catch (DataAccessException e) {
            logger.error("Error retrieving FIX_EXEC_RPT records for date: {}", date, e);
            throw new RuntimeException("Failed to retrieve FIX_EXEC_RPT records", e);
        }
    }

    /**
     * Retrieves FIX_EXEC_RPT record by EXECID
     */
    public FixExecRpt findByExecId(String execId) {
        String sql = """
                SELECT DONE_NO, EXECID, SESSION_ID, MESSAGE_TYPE, STATUS
                FROM FIX_EXEC_RPT 
                WHERE EXECID = ?
                """;

        try {
            return secondaryJdbcTemplate.queryForObject(sql, new FixExecRptRowMapper(), execId);
        } catch (DataAccessException e) {
            logger.warn("No FIX_EXEC_RPT record found for EXECID: {}", execId);
            return null;
        }
    }

    /**
     * Row mapper for FIX_EXEC_RPT table
     */
    private static class FixExecRptRowMapper implements RowMapper<FixExecRpt> {
        @Override
        public FixExecRpt mapRow(ResultSet rs, int rowNum) throws SQLException {
            FixExecRpt record = new FixExecRpt();
            record.setDoneNo(rs.getString("DONE_NO"));
            record.setExecId(rs.getString("EXECID"));
            record.setSessionId(rs.getString("SESSION_ID"));
            record.setMessageType(rs.getString("MESSAGE_TYPE"));
            record.setStatus(rs.getString("STATUS"));
            return record;
        }
    }
}