package com.example.expensemanager.client;

import com.example.expensemanager.Util;
import com.example.expensemanager.config.NotionProperties;
import com.example.expensemanager.pojos.NotionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        HttpResponse<String> response = sendQueryRequest(filter);

        if (response.statusCode() >= 400) {
            throw new IOException("Notion API request failed with HTTP status "
                    + response.statusCode()
                    + ". Response body: "
                    + abbreviate(response.body()));
        }

        JsonNode root = objectMapper.readTree(response.body());

        return util.parseNotionResponse(root.get("results"));
    }

    private HttpResponse<String> sendQueryRequest(String requestBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DATA_SOURCE_QUERY_URL_TEMPLATE.formatted(notionProperties.getDatabaseId())))
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
