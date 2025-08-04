package org.example.comparison.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Domain model representing a parsed FIX message
 */
public class FixMessage {
    private String execId;          // Tag 17
    private String custNo;          // Tag 1  
    private BigDecimal avgPrice;    // Tag 6
    private BigDecimal cumQty;      // Tag 14
    private String side;            // Tag 54 (Buy/Sell)
    private String symbol;          // Tag 55
    private String messageType;     // Tag 35
    private LocalDateTime transactTime; // Tag 60
    private String sessionId;
    private String rawMessage;
    private Map<String, String> allTags;

    public FixMessage() {}

    public FixMessage(String execId, String custNo, BigDecimal avgPrice, BigDecimal cumQty, 
                      String side, String symbol, String messageType, LocalDateTime transactTime, 
                      String sessionId, String rawMessage, Map<String, String> allTags) {
        this.execId = execId;
        this.custNo = custNo;
        this.avgPrice = avgPrice;
        this.cumQty = cumQty;
        this.side = side;
        this.symbol = symbol;
        this.messageType = messageType;
        this.transactTime = transactTime;
        this.sessionId = sessionId;
        this.rawMessage = rawMessage;
        this.allTags = allTags;
    }

    // Getters and Setters
    public String getExecId() {
        return execId;
    }

    public void setExecId(String execId) {
        this.execId = execId;
    }

    public String getCustNo() {
        return custNo;
    }

    public void setCustNo(String custNo) {
        this.custNo = custNo;
    }

    public BigDecimal getAvgPrice() {
        return avgPrice;
    }

    public void setAvgPrice(BigDecimal avgPrice) {
        this.avgPrice = avgPrice;
    }

    public BigDecimal getCumQty() {
        return cumQty;
    }

    public void setCumQty(BigDecimal cumQty) {
        this.cumQty = cumQty;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public LocalDateTime getTransactTime() {
        return transactTime;
    }

    public void setTransactTime(LocalDateTime transactTime) {
        this.transactTime = transactTime;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
    }

    public Map<String, String> getAllTags() {
        return allTags;
    }

    public void setAllTags(Map<String, String> allTags) {
        this.allTags = allTags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FixMessage that = (FixMessage) o;
        return Objects.equals(execId, that.execId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(execId);
    }

    @Override
    public String toString() {
        return "FixMessage{" +
                "execId='" + execId + '\'' +
                ", custNo='" + custNo + '\'' +
                ", avgPrice=" + avgPrice +
                ", cumQty=" + cumQty +
                ", side='" + side + '\'' +
                ", symbol='" + symbol + '\'' +
                ", messageType='" + messageType + '\'' +
                ", transactTime=" + transactTime +
                ", sessionId='" + sessionId + '\'' +
                '}';
    }
}