package com.example.expensemanager.api;

import com.example.expensemanager.controller.DashboardController;
import com.example.expensemanager.pojos.TrendResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/insights")
public class InsightsResource {

    private final DashboardController dashboardController;

    @Autowired
    InsightsResource(DashboardController dashboardController) {
        this.dashboardController = dashboardController;
    }

    @GetMapping("/trend")
    public TrendResponse trend(@RequestParam(defaultValue = "6") int months) throws IOException, InterruptedException {
        return dashboardController.getTrendData(months);
    }
}



