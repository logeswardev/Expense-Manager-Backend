package com.example.expensemanager.pojos;

import java.util.List;

public record TransactionRequest(
        String date,                // "2026-07-08" (ISO format)
        String name,                // "Name of the Transaction"
        Double amount,
        List<String> categories,          // assuming single select; use List<String> if multi_select
        String type,
        String accounts,
        String month
) {}
