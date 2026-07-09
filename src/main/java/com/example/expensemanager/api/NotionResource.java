package com.example.expensemanager.api;

import com.example.expensemanager.client.NotionClient;
import com.example.expensemanager.pojos.NotionResponse;
import com.example.expensemanager.pojos.TransactionRequest;
import com.example.expensemanager.pojos.TransactionResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping("/addTransactions")
    public TransactionResponse addTransactions(@RequestBody TransactionRequest request) throws IOException, InterruptedException {
        notionClient.createTransaction(request);
        return new TransactionResponse("Successfully updated");
    }
}
