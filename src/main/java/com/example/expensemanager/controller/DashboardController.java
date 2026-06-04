package com.example.expensemanager.controller;


import com.example.expensemanager.Util;
import com.example.expensemanager.client.NotionClient;
import com.example.expensemanager.pojos.NotionResponse;
import com.example.expensemanager.pojos.Transaction;
import com.example.expensemanager.pojos.TransactionResponse;
import com.example.expensemanager.pojos.TrendItem;
import com.example.expensemanager.pojos.TrendResponse;
import com.example.expensemanager.pojos.dashboard.Budget;
import com.example.expensemanager.pojos.dashboard.Period;
import com.example.expensemanager.pojos.dashboard.Summary;
import com.example.expensemanager.pojos.dashboard.Trend;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class DashboardController {

    private final NotionClient notionClient;
    private final Util util;

    DashboardController(NotionClient notionClient, Util util) {
        this.notionClient = notionClient;
        this.util = util;
    }

    public Summary processSummary(String fromDate, String toDate) throws IOException, InterruptedException {
        List<NotionResponse> data = getData(fromDate, toDate);
        return sumUpTheData(data, fromDate, toDate);
    }

    Summary sumUpTheData(List<NotionResponse> data, String fromDate, String toDate) throws IOException, InterruptedException {
        Summary summary = new Summary();

        addPeriods(summary, fromDate, toDate);
        calculateTotalSpend(summary, data);
        calculateTotalIncome(summary, data);
        summary.setTransactionCount(data.size());
        calculateDailyAverage(summary, fromDate, toDate);
        calculateTrend(summary, fromDate, toDate);
        calculateBudget(summary);
        
        return summary;
    }

    List<NotionResponse> getData(String fromDate, String toDate) throws IOException, InterruptedException {
        String filter = util.getByDateRangeFilter(fromDate, toDate);
        return notionClient.callNotion(filter);
    }

    void addPeriods(Summary summary, String fromDate, String toDate) {
        Period period = new Period();
        period.setFrom(fromDate);
        period.setTo(toDate);

        summary.setPeriod(period);
    }

    void calculateTotalSpend(Summary summary, List<NotionResponse> data) {
        double totalSpend = data.stream().filter(notionResponse -> notionResponse.getType().equalsIgnoreCase("Expense"))
                .mapToDouble(NotionResponse::getAmount)
                .sum();
        summary.setTotalSpend(totalSpend);
    }

    void calculateTotalIncome(Summary summary, List<NotionResponse> data) {
        double totalIncome = data.stream().filter(notionResponse -> notionResponse.getType().equalsIgnoreCase("Income"))
                .mapToDouble(NotionResponse::getAmount)
                .sum();
        summary.setTotalIncome(totalIncome);
    }

    void calculateDailyAverage(Summary summary, String fromDate, String toDate) {
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        long daysBetween = ChronoUnit.DAYS.between(from, to) + 1;
        
        double avgPerDay = summary.getTotalSpend() / daysBetween;
        summary.setDailyAverage((long) avgPerDay);
    }

    void calculateTrend(Summary summary, String fromDate, String toDate) throws IOException, InterruptedException {
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        
        // Calculate period duration
        long periodDays = ChronoUnit.DAYS.between(from, to) + 1;
        
        // Calculate previous period (same duration)
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(periodDays - 1);
        
        // Fetch previous period data
        List<NotionResponse> prevData = getData(prevFrom.toString(), prevTo.toString());
        
        // Calculate previous spend
        double prevSpend = prevData.stream()
                .filter(r -> r.getType().equalsIgnoreCase("Expense"))
                .mapToDouble(NotionResponse::getAmount)
                .sum();
        
        double currentSpend = summary.getTotalSpend();
        
        // Calculate trend
        Trend trend = new Trend();
        double difference = currentSpend - prevSpend;
        
        if (prevSpend == 0) {
            trend.setDirection(currentSpend > 0 ? "up" : "flat");
            trend.setPct(0);
        } else {
            double percentChange = (difference / prevSpend) * 100;
            trend.setPct(Math.round(percentChange * 10.0) / 10.0); // Round to 1 decimal place
            trend.setDirection(percentChange > 0 ? "up" : percentChange < 0 ? "down" : "flat");
        }
        
        trend.setLabel("FROM LAST MONTH");
        summary.setTrend(trend);
    }

    void calculateBudget(Summary summary) {
        // TODO: Fetch budget from DB/config
        // For now, using a hardcoded budget of 17,750,000
        double budgetAmount = 5500;
        double usedPct = (summary.getTotalSpend() / budgetAmount);
        
        Budget budget = new Budget();
        budget.setAmount(budgetAmount);
        budget.setUsedPct(Math.round(usedPct * 10000.0) / 10000.0); // Round to 4 decimal places
        
        summary.setBudget(budget);
    }

    public TransactionResponse getRecentTransactions(int limit) throws IOException, InterruptedException {
        // Fetch all transactions from the last 90 days
        LocalDate today = LocalDate.now();
        LocalDate ninetyDaysAgo = today.minusDays(90);
        
        List<NotionResponse> allTransactions = getData(ninetyDaysAgo.toString(), today.toString());
        
        // Sort by date descending (most recent first)
        List<NotionResponse> sorted = allTransactions.stream()
                .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
                .limit(limit)
                .toList();
        
        // Convert to Transaction objects
        List<Transaction> transactions = new ArrayList<>();
        for (NotionResponse nr : sorted) {
            Transaction tx = new Transaction();
            tx.setId("tx_" + nr.hashCode()); // Simple ID generation
            tx.setMerchant(nr.getName());
            tx.setCategory(nr.getCategory());
            tx.setType(nr.getType());
            tx.setAmount(nr.getAmount());
            tx.setDate(nr.getDate());
            transactions.add(tx);
        }
        
        TransactionResponse response = new TransactionResponse();
        response.setItems(transactions);
        return response;
    }

    public TrendResponse getTrendData(int months) throws IOException, InterruptedException {
        List<TrendItem> series = new ArrayList<>();
        LocalDate today = LocalDate.now();
        
        // Get data for the last N months
        for (int i = months - 1; i >= 0; i--) {
            YearMonth targetMonth = YearMonth.from(today.minusMonths(i));
            LocalDate monthStart = targetMonth.atDay(1);
            LocalDate monthEnd = targetMonth.atEndOfMonth();
            
            // Fetch data for this month
            List<NotionResponse> monthData = getData(monthStart.toString(), monthEnd.toString());
            
            // Sum expenses only
            double monthTotal = monthData.stream()
                    .filter(r -> r.getType().equalsIgnoreCase("Expense"))
                    .mapToDouble(NotionResponse::getAmount)
                    .sum();
            
            TrendItem item = new TrendItem();
            item.setMonth(targetMonth.toString()); // Format: "2023-10"
            item.setAmount(monthTotal);
            series.add(item);
        }
        
        TrendResponse response = new TrendResponse();
        response.setSeries(series);
        return response;
    }
}
