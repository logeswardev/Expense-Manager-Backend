package com.example.expensemanager.client;

import com.example.expensemanager.Util;
import com.example.expensemanager.config.NotionProperties;
import com.example.expensemanager.pojos.NotionResponse;
import com.example.expensemanager.pojos.TransactionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@Component
public class NotionClient {

    private static final Logger log = LoggerFactory.getLogger(NotionClient.class);
    private static final String DATA_SOURCE_QUERY_URL_TEMPLATE = "https://api.notion.com/v1/data_sources/%s/query";
    private static final String DATABASE_RETRIEVE_URL_TEMPLATE = "https://api.notion.com/v1/databases/%s";
    private static final String DATA_SOURCE_RETRIEVE_URL_TEMPLATE = "https://api.notion.com/v1/data_sources/%s";
    private static final String PAGE_CREATE_URL = "https://api.notion.com/v1/pages";

    private final NotionProperties notionProperties;
    private final Util util;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public NotionClient(NotionProperties notionProperties, Util util) {
        this.notionProperties = notionProperties;
        this.util = util;
        this.client = createHttpClient(notionProperties);
    }

    public List<NotionResponse> callNotion(String filter) throws IOException, InterruptedException {
        HttpResponse<String> response = sendQueryRequest(filter, notionProperties.getDatabaseId());

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
        }

        JsonNode root = objectMapper.readTree(response.body());

        return util.parseNotionResponse(root.get("results"));
    }

    private HttpResponse<String> sendQueryRequest(String requestBody, String databaseId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DATA_SOURCE_QUERY_URL_TEMPLATE.formatted(databaseId)))
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

    public void createTransaction(TransactionRequest txn) throws IOException, InterruptedException {
        // Discover the related database IDs directly from the Transactions data source schema,
        // instead of hardcoding them (mirrors the Apple Shortcuts flow).
        JsonNode schema = fetchTransactionsDataSourceSchema();
        String transactionsDataSourceId = extractSchemaDataSourceId(schema);

        String categoriesDataSourceId = extractRelationQueryableDataSourceId(schema, "Categories");
        String accountsDataSourceId = extractRelationQueryableDataSourceId(schema, "Accounts");
        String monthsDataSourceId = extractRelationQueryableDataSourceId(schema, "Months");

        String requestBody = buildTransactionPayload(
                txn,
                transactionsDataSourceId,
                categoriesDataSourceId,
                accountsDataSourceId,
                monthsDataSourceId);

        HttpResponse<String> response = sendCreatePageRequest(requestBody);

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
        }
    }

    /**
     * Under Notion's multi-source database model, GET /v1/databases/{id} no longer returns
     * a `properties` object directly — it only returns metadata plus a `data_sources` array.
     * The actual schema (with `properties`, including relation `database_id`s) lives on the
     * data source itself, so this does a two-step fetch: database -> data_sources[0].id -> schema.
     */
    private JsonNode fetchTransactionsDataSourceSchema() throws IOException, InterruptedException {
        JsonNode database = fetchJson(DATABASE_RETRIEVE_URL_TEMPLATE.formatted(notionProperties.getDatabaseId()));

        JsonNode dataSources = database.get("data_sources");
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IOException("Transactions database has no data_sources; cannot resolve its schema.");
        }

        String dataSourceId = dataSources.get(0).get("id").asText();

        return fetchJson(DATA_SOURCE_RETRIEVE_URL_TEMPLATE.formatted(dataSourceId));
    }

    private JsonNode fetchJson(String url) throws IOException, InterruptedException {
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

    private String extractRelationQueryableDataSourceId(JsonNode transactionsSchema, String propertyName)
            throws IOException, InterruptedException {
        JsonNode property = transactionsSchema.get("properties").get(propertyName);
        if (property == null || property.get("relation") == null) {
            throw new IOException("Property `" + propertyName + "` is missing or is not a relation on the Transactions data source.");
        }

        JsonNode relation = property.get("relation");

        // /v1/data_sources/{id}/query requires a data_source_id.
        // Some Notion versions return relation.database_id, so resolve it via /v1/databases/{id} -> data_sources[0].id.
        JsonNode dataSourceIdNode = relation.get("data_source_id");
        if (dataSourceIdNode != null && hasText(dataSourceIdNode.asText())) {
            return dataSourceIdNode.asText();
        }

        JsonNode databaseIdNode = relation.get("database_id");
        if (databaseIdNode != null && hasText(databaseIdNode.asText())) {
            return resolveDataSourceIdFromDatabaseId(databaseIdNode.asText(), propertyName);
        }

        throw new IOException("Could not find a linked database/data source id for relation property `" + propertyName + "`.");
    }

    private String resolveDataSourceIdFromDatabaseId(String databaseId, String propertyName)
            throws IOException, InterruptedException {
        JsonNode database = fetchJson(DATABASE_RETRIEVE_URL_TEMPLATE.formatted(databaseId));
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

    private String extractSchemaDataSourceId(JsonNode schema) throws IOException {
        JsonNode schemaId = schema.get("id");
        if (schemaId == null || !hasText(schemaId.asText())) {
            throw new IOException("Transactions data source schema is missing `id`.");
        }
        return schemaId.asText();
    }

    private String buildTransactionPayload(TransactionRequest txn,
                                           String transactionsDataSourceId,
                                           String categoriesDataSourceId,
                                           String accountsDataSourceId,
                                           String monthsDataSourceId)
            throws IOException, InterruptedException {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode parent = root.putObject("parent");
        parent.put("data_source_id", transactionsDataSourceId);

        ObjectNode properties = root.putObject("properties");

        // Title property
        ObjectNode nameProp = properties.putObject("Name");
        ArrayNode titleArray = nameProp.putArray("title");
        ObjectNode titleText = titleArray.addObject();
        titleText.putObject("text").put("content", txn.name());

        // Date property
        ObjectNode dateProp = properties.putObject("Date");
        dateProp.putObject("date").put("start", txn.date());

        // Number property
        properties.putObject("Amount").put("number", txn.amount());

        // Select property
        properties.putObject("Type").putObject("select").put("name", txn.type());

        // Categories relation (multi)
        ArrayNode categoriesRelation = properties.putObject("Categories").putArray("relation");
        for (String category : txn.categories()) {
            String categoryId = findPageIdByName(categoriesDataSourceId, category);
            categoriesRelation.addObject().put("id", categoryId);
        }

        // Accounts relation (single)
        String accountId = findPageIdByName(accountsDataSourceId, txn.accounts());
        properties.putObject("Accounts").putArray("relation").addObject().put("id", accountId);

        // Months relation (single)
        String monthId = findPageIdByName(monthsDataSourceId, txn.month());
        properties.putObject("Months").putArray("relation").addObject().put("id", monthId);

        return objectMapper.writeValueAsString(root);
    }

    private String findPageIdByName(String databaseId, String name) throws IOException, InterruptedException {
        HttpResponse<String> response = sendQueryRequest("", databaseId);

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

        throw new IOException("No matching page found in database " + databaseId + " for name: " + name);
    }

    private HttpResponse<String> sendCreatePageRequest(String requestBody) throws IOException, InterruptedException {
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


    private HttpClient createHttpClient(NotionProperties notionProperties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, notionProperties.getConnectTimeoutSeconds())));

        SSLContext sslContext = buildSslContext(notionProperties.getSsl());
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        return builder.build();
    }

    private SSLContext buildSslContext(NotionProperties.Ssl sslProperties) {
        if (sslProperties == null) {
            return null;
        }

        if (sslProperties.isInsecureSkipVerification()) {
            log.warn("Insecure SSL mode enabled for Notion client: certificate validation is disabled.");
            return buildInsecureSslContext();
        }

        if (!sslProperties.hasTrustStore()) {
            return null;
        }

        try (InputStream inputStream = Files.newInputStream(Path.of(sslProperties.getTrustStore()))) {
            KeyStore trustStore = KeyStore.getInstance(resolveTrustStoreType(sslProperties.getTrustStoreType()));
            trustStore.load(inputStream, resolvePassword(sslProperties.getTrustStorePassword()));

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            log.info("Using custom truststore for Notion HTTPS connections.");
            return sslContext;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load the Notion truststore from `"
                    + sslProperties.getTrustStore()
                    + "`. Check notion.ssl.trust-store, notion.ssl.trust-store-password, and notion.ssl.trust-store-type.", ex);
        }
    }

    private SSLContext buildInsecureSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new InsecureTrustManager()}, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to initialize insecure SSL context for Notion client.", ex);
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

    private char[] resolvePassword(String password) {
        return password == null ? null : password.toCharArray();
    }

    private String resolveTrustStoreType(String trustStoreType) {
        return hasText(trustStoreType) ? trustStoreType : KeyStore.getDefaultType();
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

    private static final class InsecureTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}