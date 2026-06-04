package com.example.expensemanager.pojos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotionResponse {

    private double amount;
    private String date;
    private String personSpent;
    private String type;
    private String month;
    private String category;
    private String name;

}
