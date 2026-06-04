package com.example.expensemanager.api;

import com.example.expensemanager.client.NotionClient;
import com.example.expensemanager.pojos.NotionResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/notion/data")
public class NotionResource {

    private final NotionClient notionClient;

    public NotionResource(NotionClient notionClient) {
        this.notionClient = notionClient;
    }

    @PostMapping("/fetchTransactions")
    public List<NotionResponse> fetchTransactions() throws IOException, InterruptedException {
        return notionClient.callNotion("");
    }
}
