package com.amrap.application.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Models, SQL limits, and read-only chat DB settings for the routed chat pipeline. */
@ConfigurationProperties(prefix = "amrap.chat")
public class ChatProperties {

    private String classifyModel = "qwen2.5:3b";
    private String sqlModel = "qwen2.5-coder:7b";
    private String answerModel = "qwen2.5:7b";
    private String ollamaBaseUrl = "http://localhost:11434";
    private int sqlMaxRows = 200;
    private int sqlTimeoutMs = 3000;
    private int ollamaTimeoutMs = 120_000;

    private final Datasource datasource = new Datasource();

    public String getClassifyModel() {
        return classifyModel;
    }

    public void setClassifyModel(String classifyModel) {
        this.classifyModel = classifyModel;
    }

    public String getSqlModel() {
        return sqlModel;
    }

    public void setSqlModel(String sqlModel) {
        this.sqlModel = sqlModel;
    }

    public String getAnswerModel() {
        return answerModel;
    }

    public void setAnswerModel(String answerModel) {
        this.answerModel = answerModel;
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    public int getSqlMaxRows() {
        return sqlMaxRows;
    }

    public void setSqlMaxRows(int sqlMaxRows) {
        this.sqlMaxRows = sqlMaxRows;
    }

    public int getSqlTimeoutMs() {
        return sqlTimeoutMs;
    }

    public void setSqlTimeoutMs(int sqlTimeoutMs) {
        this.sqlTimeoutMs = sqlTimeoutMs;
    }

    public int getOllamaTimeoutMs() {
        return ollamaTimeoutMs;
    }

    public void setOllamaTimeoutMs(int ollamaTimeoutMs) {
        this.ollamaTimeoutMs = ollamaTimeoutMs;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public boolean chatSqlConfigured() {
        return datasource.getUrl() != null
                && !datasource.getUrl().isBlank()
                && datasource.getUsername() != null
                && !datasource.getUsername().isBlank()
                && datasource.getPassword() != null
                && !datasource.getPassword().isBlank();
    }

    public static class Datasource {
        private String url = "";
        private String username = "amrap_chat_ro";
        private String password = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
