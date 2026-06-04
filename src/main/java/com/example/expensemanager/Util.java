package com.example.expensemanager;

import com.example.expensemanager.pojos.NotionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Util {

    private String buildRequestFilter(String propertyName, String subPropertyName, String value) {
        return """
                {
                  "filter": {
                    "property": "%s",
                    "%s": {
                      "equals": "%s"
                    }
                  }
                }
                """.formatted(propertyName, subPropertyName, value);
    }

    public String getByDateFilter(String date) {
        return buildRequestFilter("Date", "date", date);
    }

    public String getByDateRangeFilter(String fromDate, String toDate) {
        return """
                {
                  "filter": {
                    "and": [
                      {
                        "property": "Date",
                        "date": {
                          "on_or_after": "%s"
                        }
                      },
                      {
                        "property": "Date",
                        "date": {
                          "on_or_before": "%s"
                        }
                      }
                    ]
                  }
                }
                """.formatted(fromDate, toDate);
    }

    public String getByAmountFilter(String amount) {
        return buildRequestFilter("Amount", "amount", amount);
    }

    public String getByCategoryFilter(String category) {
        return buildRequestFilter("Category", "select", category);
    }

    /**
     * Helper method to safely extract text from nested rollup properties
     */
    private String extractRollupText(JsonNode properties, String propertyName) {
        JsonNode property = properties.at("/" + propertyName + "/rollup/array/0/title/0");
        if (property.isMissingNode()) {
            return "";
        }
        return property.at("/text/content").asText();
    }

    public List<NotionResponse> parseNotionResponse(JsonNode results) {
        List<NotionResponse> list = new ArrayList<>();

        for (JsonNode item : results) {
            JsonNode properties = item.get("properties");
            if (properties == null || properties.isMissingNode()) {
                continue;
            }

            NotionResponse dto = new NotionResponse();

            // Amount
            double amount = properties.at("/Amount/number").asDouble(0);
            dto.setAmount(amount);

            // Date
            String date = properties.at("/Date/date/start").asText();
            dto.setDate(date);

            // Name
            String name = properties.at("/Name/title/0/plain_text").asText();
            dto.setName(name);

            // Type (Expense)
            String type = properties.at("/Type/select/name").asText();
            dto.setType(type);

            // Category
            dto.setCategory(extractRollupText(properties, "Display Categories"));

            // Display Month
            dto.setMonth(extractRollupText(properties, "Display Months"));

            // Display Account
            dto.setPersonSpent(extractRollupText(properties, "Display Accounts"));

            list.add(dto);
        }
        return list;
    }
}
