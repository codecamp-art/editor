package org.example.comparison.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain model representing a comparison discrepancy between database and FIX log
 */
public class ComparisonResult {
    private String doneNo;
    private String execId;
    private String field;
    private String databaseValue;
    private String fixValue;
    private String discrepancyType;
    private LocalDateTime comparisonTime;
    private String sessionId;

    public enum DiscrepancyType {
        MISSING_IN_FIX,
        MISSING_IN_DATABASE,
        VALUE_MISMATCH,
        ORPHANED_FIX_RECORD
    }

    public ComparisonResult() {
        this.comparisonTime = LocalDateTime.now();
    }

    public ComparisonResult(String doneNo, String execId, String field, 
                           String databaseValue, String fixValue, 
                           String discrepancyType, String sessionId) {
        this.doneNo = doneNo;
        this.execId = execId;
        this.field = field;
        this.databaseValue = databaseValue;
        this.fixValue = fixValue;
        this.discrepancyType = discrepancyType;
        this.sessionId = sessionId;
        this.comparisonTime = LocalDateTime.now();
    }

    // Getters and Setters
    public String getDoneNo() {
        return doneNo;
    }

    public void setDoneNo(String doneNo) {
        this.doneNo = doneNo;
    }

    public String getExecId() {
        return execId;
    }

    public void setExecId(String execId) {
        this.execId = execId;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getDatabaseValue() {
        return databaseValue;
    }

    public void setDatabaseValue(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String getFixValue() {
        return fixValue;
    }

    public void setFixValue(String fixValue) {
        this.fixValue = fixValue;
    }

    public String getDiscrepancyType() {
        return discrepancyType;
    }

    public void setDiscrepancyType(String discrepancyType) {
        this.discrepancyType = discrepancyType;
    }

    public LocalDateTime getComparisonTime() {
        return comparisonTime;
    }

    public void setComparisonTime(LocalDateTime comparisonTime) {
        this.comparisonTime = comparisonTime;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ComparisonResult that = (ComparisonResult) o;
        return Objects.equals(doneNo, that.doneNo) &&
               Objects.equals(execId, that.execId) &&
               Objects.equals(field, that.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(doneNo, execId, field);
    }

    @Override
    public String toString() {
        return "ComparisonResult{" +
                "doneNo='" + doneNo + '\'' +
                ", execId='" + execId + '\'' +
                ", field='" + field + '\'' +
                ", databaseValue='" + databaseValue + '\'' +
                ", fixValue='" + fixValue + '\'' +
                ", discrepancyType='" + discrepancyType + '\'' +
                ", comparisonTime=" + comparisonTime +
                ", sessionId='" + sessionId + '\'' +
                '}';
    }
}