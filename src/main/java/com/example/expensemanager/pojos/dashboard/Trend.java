package com.example.expensemanager.pojos.dashboard;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Trend {

    private String direction;
    private double pct;
    private String label;
}
