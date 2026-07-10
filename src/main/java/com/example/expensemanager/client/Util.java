package com.example.expensemanager.client;

import com.example.expensemanager.config.NotionProperties;
import com.example.expensemanager.pojos.TransactionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.net.ssl.SSLHandshakeException;

@Component("notionClientUtil")
public class Util {

    private static final String DATA_SOURCE_QUERY_URL_TEMPLATE = "https://api.notion.com/v1/data_sources/%s/query";
    private static final String DATABASE_RETRIEVE_URL_TEMPLATE = "https://api.notion.com/v1/databases/%s";
    private static final String DATA_SOURCE_RETRIEVE_URL_TEMPLATE = "https://api.notion.com/v1/data_sources/%s";
    private static final String PAGE_CREATE_URL = "https://api.notion.com/v1/pages";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpResponse<String> sendQueryRequest(HttpClient client,
                                                 NotionProperties notionProperties,
                                                 String requestBody,
                                                 String dataSourceId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DATA_SOURCE_QUERY_URL_TEMPLATE.formatted(dataSourceId)))
                .header("Authorization", "Bearer " + notionProperties.getToken())
                .header("Notion-Version", notionProperties.getVersion())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (SSLHandshakeException ex) {
            throw buildTlsConfigurationException(ex);
        }
    }

    public JsonNode fetchTransactionsDataSourceSchema(HttpClient client,
                                                      NotionProperties notionProperties) throws IOException, InterruptedException {
        JsonNode database = fetchJson(client,
                notionProperties,
                DATABASE_RETRIEVE_URL_TEMPLATE.formatted(notionProperties.getDatabaseId()));

        JsonNode dataSources = database.get("data_sources");
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IOException("Transactions database has no data_sources; cannot resolve its schema.");
        }

        String dataSourceId = dataSources.get(0).get("id").asText();
        return fetchJson(client, notionProperties, DATA_SOURCE_RETRIEVE_URL_TEMPLATE.formatted(dataSourceId));
    }

    private JsonNode fetchJson(HttpClient client,
                               NotionProperties notionProperties,
                               String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + notionProperties.getToken())
                .header("Notion-Version", notionProperties.getVersion())
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (SSLHandshakeException ex) {
            throw buildTlsConfigurationException(ex);
        }

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
        }

        return objectMapper.readTree(response.body());
    }

    public String extractRelationQueryableDataSourceId(HttpClient client,
                                                       NotionProperties notionProperties,
                                                       JsonNode transactionsSchema,
                                                       String propertyName) throws IOException, InterruptedException {
        JsonNode property = transactionsSchema.get("properties").get(propertyName);
        if (property == null || property.get("relation") == null) {
            throw new IOException("Property `" + propertyName + "` is missing or is not a relation on the Transactions data source.");
        }

        JsonNode relation = property.get("relation");

        JsonNode dataSourceIdNode = relation.get("data_source_id");
        if (dataSourceIdNode != null && hasText(dataSourceIdNode.asText())) {
            return dataSourceIdNode.asText();
        }

        JsonNode databaseIdNode = relation.get("database_id");
        if (databaseIdNode != null && hasText(databaseIdNode.asText())) {
            return resolveDataSourceIdFromDatabaseId(client, notionProperties, databaseIdNode.asText(), propertyName);
        }

        throw new IOException("Could not find a linked database/data source id for relation property `" + propertyName + "`.");
    }

    private String resolveDataSourceIdFromDatabaseId(HttpClient client,
                                                     NotionProperties notionProperties,
                                                     String databaseId,
                                                     String propertyName) throws IOException, InterruptedException {
        JsonNode database = fetchJson(client, notionProperties, DATABASE_RETRIEVE_URL_TEMPLATE.formatted(databaseId));
        JsonNode dataSources = database.get("data_sources");

        if (dataSources == null || dataSources.isEmpty()) {
            throw new IOException("Relation `" + propertyName + "` points to database `" + databaseId
                    + "`, but no data_sources were found.");
        }

        JsonNode firstDataSourceId = dataSources.get(0).get("id");
        if (firstDataSourceId == null || !hasText(firstDataSourceId.asText())) {
            throw new IOException("Relation `" + propertyName + "` points to database `" + databaseId
                    + "`, but data_sources[0].id is missing.");
        }

        return firstDataSourceId.asText();
    }

    public String extractSchemaDataSourceId(JsonNode schema) throws IOException {
        JsonNode schemaId = schema.get("id");
        if (schemaId == null || !hasText(schemaId.asText())) {
            throw new IOException("Transactions data source schema is missing `id`.");
        }
        return schemaId.asText();
    }

    public String buildTransactionPayload(HttpClient client,
                                          NotionProperties notionProperties,
                                          TransactionRequest txn,
                                          String transactionsDataSourceId,
                                          String categoriesDataSourceId,
                                          String accountsDataSourceId,
                                          String monthsDataSourceId) throws IOException, InterruptedException {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode parent = root.putObject("parent");
        parent.put("data_source_id", transactionsDataSourceId);

        ObjectNode properties = root.putObject("properties");

        ObjectNode nameProp = properties.putObject("Name");
        ArrayNode titleArray = nameProp.putArray("title");
        ObjectNode titleText = titleArray.addObject();
        titleText.putObject("text").put("content", txn.name());

        ObjectNode dateProp = properties.putObject("Date");
        dateProp.putObject("date").put("start", txn.date());

        properties.putObject("Amount").put("number", txn.amount());
        properties.putObject("Type").putObject("select").put("name", txn.type());

        ArrayNode categoriesRelation = properties.putObject("Categories").putArray("relation");

        String categoryId = findPageIdByName(client, notionProperties, categoriesDataSourceId, txn.categories());
        categoriesRelation.addObject().put("id", categoryId);

        String accountId = findPageIdByName(client, notionProperties, accountsDataSourceId, txn.accounts());
        properties.putObject("Accounts").putArray("relation").addObject().put("id", accountId);

        String monthId = findPageIdByName(client, notionProperties, monthsDataSourceId, txn.month());
        properties.putObject("Months").putArray("relation").addObject().put("id", monthId);

        return objectMapper.writeValueAsString(root);
    }

    private String findPageIdByName(HttpClient client,
                                    NotionProperties notionProperties,
                                    String dataSourceId,
                                    String name) throws IOException, InterruptedException {
        HttpResponse<String> response = sendQueryRequest(client, notionProperties, "", dataSourceId);

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode results = root.get("results");

        for (JsonNode page : results) {
            JsonNode titleArray = page.get("properties").get("Name").get("title");
            if (titleArray != null && !titleArray.isEmpty()) {
                String title = titleArray.get(0).get("plain_text").asText();
                if (title.equalsIgnoreCase(name)) {
                    return page.get("id").asText();
                }
            }
        }

        throw new IOException("No matching page found in data source " + dataSourceId + " for name: " + name);
    }

    public HttpResponse<String> sendCreatePageRequest(HttpClient client,
                                                      NotionProperties notionProperties,
                                                      String requestBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PAGE_CREATE_URL))
                .header("Authorization", "Bearer " + notionProperties.getToken())
                .header("Notion-Version", notionProperties.getVersion())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (SSLHandshakeException ex) {
            throw buildTlsConfigurationException(ex);
        }
    }

    private SSLHandshakeException buildTlsConfigurationException(SSLHandshakeException ex) {
        SSLHandshakeException wrapped = new SSLHandshakeException(
                "TLS handshake with Notion failed. This usually means the JVM does not trust the certificate chain presented on your network. "
                        + "If you are behind a corporate proxy or custom CA, import that CA into the JVM truststore or configure "
                        + "notion.ssl.trust-store, notion.ssl.trust-store-password, and notion.ssl.trust-store-type.");
        wrapped.initCause(ex);
        return wrapped;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String abbreviate(String responseBody) {
        if (responseBody == null || responseBody.length() <= 500) {
            return responseBody;
        }

        return responseBody.substring(0, 500) + "...";
    }

}
