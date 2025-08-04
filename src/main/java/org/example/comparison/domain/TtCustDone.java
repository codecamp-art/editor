package org.example.comparison.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain model representing the TT_CUST_DONE database table
 */
public class TtCustDone {
    private String doneNo;
    private BigDecimal donePrice;
    private BigDecimal doneQty;
    private String contractCode;
    private String bsFlag;
    private String custNo;
    private LocalDateTime doneTime;
    private String status;

    public TtCustDone() {}

    public TtCustDone(String doneNo, BigDecimal donePrice, BigDecimal doneQty, 
                      String contractCode, String bsFlag, String custNo, 
                      LocalDateTime doneTime, String status) {
        this.doneNo = doneNo;
        this.donePrice = donePrice;
        this.doneQty = doneQty;
        this.contractCode = contractCode;
        this.bsFlag = bsFlag;
        this.custNo = custNo;
        this.doneTime = doneTime;
        this.status = status;
    }

    // Getters and Setters
    public String getDoneNo() {
        return doneNo;
    }

    public void setDoneNo(String doneNo) {
        this.doneNo = doneNo;
    }

    public BigDecimal getDonePrice() {
        return donePrice;
    }

    public void setDonePrice(BigDecimal donePrice) {
        this.donePrice = donePrice;
    }

    public BigDecimal getDoneQty() {
        return doneQty;
    }

    public void setDoneQty(BigDecimal doneQty) {
        this.doneQty = doneQty;
    }

    public String getContractCode() {
        return contractCode;
    }

    public void setContractCode(String contractCode) {
        this.contractCode = contractCode;
    }

    public String getBsFlag() {
        return bsFlag;
    }

    public void setBsFlag(String bsFlag) {
        this.bsFlag = bsFlag;
    }

    public String getCustNo() {
        return custNo;
    }

    public void setCustNo(String custNo) {
        this.custNo = custNo;
    }

    public LocalDateTime getDoneTime() {
        return doneTime;
    }

    public void setDoneTime(LocalDateTime doneTime) {
        this.doneTime = doneTime;
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
        TtCustDone that = (TtCustDone) o;
        return Objects.equals(doneNo, that.doneNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(doneNo);
    }

    @Override
    public String toString() {
        return "TtCustDone{" +
                "doneNo='" + doneNo + '\'' +
                ", donePrice=" + donePrice +
                ", doneQty=" + doneQty +
                ", contractCode='" + contractCode + '\'' +
                ", bsFlag='" + bsFlag + '\'' +
                ", custNo='" + custNo + '\'' +
                ", doneTime=" + doneTime +
                ", status='" + status + '\'' +
                '}';
    }
}