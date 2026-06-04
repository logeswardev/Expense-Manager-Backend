package com.example.expensemanager.pojos.dashboard;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Summary {

    private Period period;
    private double totalSpend;
    private double totalIncome;
    private long transactionCount;
    private long dailyAverage;
    private Trend trend;
    private Budget budget;
}
