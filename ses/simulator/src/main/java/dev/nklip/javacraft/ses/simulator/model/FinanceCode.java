package dev.nklip.javacraft.ses.simulator.model;

/**
 * Created by nikilipa on 7/23/16.
 */
public class FinanceCode {

    private Long id;

    private String financeCode;

    private int days;

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFinanceCode() {
        return financeCode;
    }

    public void setFinanceCode(String financeCode) {
        this.financeCode = financeCode;
    }

}
