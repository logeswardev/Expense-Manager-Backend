package com.example.expensemanager.client;

import com.example.expensemanager.config.NotionProperties;
import com.example.expensemanager.pojos.NotionResponse;
import com.example.expensemanager.pojos.TransactionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
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
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@Component
public class NotionClient {

    private static final Logger log = LoggerFactory.getLogger(NotionClient.class);
    private final NotionProperties notionProperties;
    private final com.example.expensemanager.Util util;
    private final Util clientUtil;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public NotionClient(NotionProperties notionProperties,
                        com.example.expensemanager.Util util,
                        Util clientUtil) {
        this.notionProperties = notionProperties;
        this.util = util;
        this.clientUtil = clientUtil;
        this.client = createHttpClient(notionProperties);
    }

    public List<NotionResponse> callNotion(String filter) throws IOException, InterruptedException {
        HttpResponse<String> response = clientUtil.sendQueryRequest(
                client,
                notionProperties,
                filter,
                notionProperties.getDatabaseId());

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
        }

        JsonNode root = objectMapper.readTree(response.body());

        return util.parseNotionResponse(root.get("results"));
    }

    public void createTransaction(TransactionRequest txn) throws IOException, InterruptedException {
        JsonNode schema = clientUtil.fetchTransactionsDataSourceSchema(client, notionProperties);
        String transactionsDataSourceId = clientUtil.extractSchemaDataSourceId(schema);

        String categoriesDataSourceId = clientUtil.extractRelationQueryableDataSourceId(
                client,
                notionProperties,
                schema,
                "Categories");
        String accountsDataSourceId = clientUtil.extractRelationQueryableDataSourceId(
                client,
                notionProperties,
                schema,
                "Accounts");
        String monthsDataSourceId = clientUtil.extractRelationQueryableDataSourceId(
                client,
                notionProperties,
                schema,
                "Months");

        String requestBody = clientUtil.buildTransactionPayload(
                client,
                notionProperties,
                txn,
                transactionsDataSourceId,
                categoriesDataSourceId,
                accountsDataSourceId,
                monthsDataSourceId);

        HttpResponse<String> response = clientUtil.sendCreatePageRequest(client, notionProperties, requestBody);

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
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