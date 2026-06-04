package com.example.expensemanager.api;

import com.example.expensemanager.controller.DashboardController;
import com.example.expensemanager.client.NotionClient;
import com.example.expensemanager.pojos.NotionResponse;
import com.example.expensemanager.pojos.TransactionResponse;
import com.example.expensemanager.pojos.TrendResponse;
import com.example.expensemanager.pojos.dashboard.Profile;
import com.example.expensemanager.pojos.dashboard.Summary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@RestController
@RequestMapping("/dashboard")
public class DashboardResource {

    private final DashboardController dashboardController;
    private final NotionClient notionClient;

    @Autowired
    DashboardResource(DashboardController dashboardController, NotionClient notionClient) {
        this.dashboardController = dashboardController;
        this.notionClient = notionClient;
    }

    @GetMapping("/me")
    public Profile profile(){
        Profile profile = new Profile();
        profile.setName("Logeswar and Devika");
        profile.setGreeting("Hello Loki and Devi");
        profile.setAvatarUrl(null);
        profile.setCurrency("CAD");
        return profile;
    }

    @GetMapping("/summary")
    public Summary summary(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) throws IOException, InterruptedException {
        LocalDate today = LocalDate.now();
        LocalDate effectiveFrom;
        LocalDate effectiveTo;

        switch (period.toLowerCase()) {
            case "today" -> {
                effectiveFrom = today;
                effectiveTo = today;
            }
            case "week" -> {
                effectiveFrom = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                effectiveTo = today;
            }
            case "month" -> {
                effectiveFrom = today.withDayOfMonth(1);
                effectiveTo = today;
            }
            case "range" -> {
                if (from == null || to == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "from and to are required when period=range"
                    );
                }
                if (from.isAfter(to)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "from must be on or before to"
                    );
                }
                effectiveFrom = from;
                effectiveTo = to;
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "period must be one of today, week, month, range"
            );
        }

        return dashboardController.processSummary(effectiveFrom.toString(), effectiveTo.toString());
    }

    @GetMapping("/transactions/recent")
    public TransactionResponse recentTransactions(@RequestParam(defaultValue = "4") int limit) throws IOException, InterruptedException {
        return dashboardController.getRecentTransactions(limit);
    }

    @GetMapping("/trend")
    public TrendResponse trend(@RequestParam(defaultValue = "6") int months) throws IOException, InterruptedException {
        return dashboardController.getTrendData(months);
    }

    @PostMapping("/notion/fetchTransactions")
    public List<NotionResponse> fetchTransactions() throws IOException, InterruptedException {
        return notionClient.callNotion("");
    }
}
