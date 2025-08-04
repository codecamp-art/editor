package org.example.comparison.domain;

import java.util.Objects;

/**
 * Domain model representing the FIX_EXEC_RPT database table
 */
public class FixExecRpt {
    private String doneNo;
    private String execId;
    private String sessionId;
    private String messageType;
    private String status;

    public FixExecRpt() {}

    public FixExecRpt(String doneNo, String execId, String sessionId, String messageType, String status) {
        this.doneNo = doneNo;
        this.execId = execId;
        this.sessionId = sessionId;
        this.messageType = messageType;
        this.status = status;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FixExecRpt that = (FixExecRpt) o;
        return Objects.equals(execId, that.execId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(execId);
    }

    @Override
    public String toString() {
        return "FixExecRpt{" +
                "doneNo='" + doneNo + '\'' +
                ", execId='" + execId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", messageType='" + messageType + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}