package com.example.expensemanager.pojos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    private String id;
    private String merchant;
    private String category;
    private String type;
    private double amount;
    private String date;
}

