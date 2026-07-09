package com.example.expensemanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notion")
public class NotionProperties {

	private String token;
	private String databaseId;
	private String categoriesDbId;
	private String accountsDbId;
	private String monthsDbId;
	private String version = "2022-06-28";
	private int pageSize = 100;
	private int connectTimeoutSeconds = 20;
	private final Ssl ssl = new Ssl();

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getDatabaseId() {
		return databaseId;
	}

	public void setDatabaseId(String databaseId) {
		this.databaseId = databaseId;
	}

	public String getCategoriesDbId() {
		return categoriesDbId;
	}

	public void setCategoriesDbId(String categoriesDbId) {
		this.categoriesDbId = categoriesDbId;
	}

	public String getAccountsDbId() {
		return accountsDbId;
	}

	public void setAccountsDbId(String accountsDbId) {
		this.accountsDbId = accountsDbId;
	}

	public String getMonthsDbId() {
		return monthsDbId;
	}

	public void setMonthsDbId(String monthsDbId) {
		this.monthsDbId = monthsDbId;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public int getPageSize() {
		return pageSize;
	}

	public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
	}

	public int getConnectTimeoutSeconds() {
		return connectTimeoutSeconds;
	}

	public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
		this.connectTimeoutSeconds = connectTimeoutSeconds;
	}

	public Ssl getSsl() {
		return ssl;
	}

	public boolean hasCredentials() {
		return hasText(token) && hasText(databaseId);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public static class Ssl {
		private String trustStore;
		private String trustStorePassword;
		private String trustStoreType;
		private boolean insecureSkipVerification;

		public String getTrustStore() {
			return trustStore;
		}

		public void setTrustStore(String trustStore) {
			this.trustStore = trustStore;
		}

		public String getTrustStorePassword() {
			return trustStorePassword;
		}

		public void setTrustStorePassword(String trustStorePassword) {
			this.trustStorePassword = trustStorePassword;
		}

		public String getTrustStoreType() {
			return trustStoreType;
		}

		public void setTrustStoreType(String trustStoreType) {
			this.trustStoreType = trustStoreType;
		}

		public boolean isInsecureSkipVerification() {
			return insecureSkipVerification;
		}

		public void setInsecureSkipVerification(boolean insecureSkipVerification) {
			this.insecureSkipVerification = insecureSkipVerification;
		}

		public boolean hasTrustStore() {
			return trustStore != null && !trustStore.isBlank();
		}
	}
}
