package com.distributedjudge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "judge")
public class JudgeProperties {
    private int maxCodeLength = 8000;
    private int memoryLimitMb = 256;
    private int timeoutMs = 5000;
    private int sessionTtlHours = 24;
    private String javaImage = "eclipse-temurin:21-jdk-alpine";
    private String pythonImage = "python:3.12-alpine";
    private String cImage = "gcc:14-bookworm";
    private String cppImage = "gcc:14-bookworm";
    private RateLimit rateLimit = new RateLimit();

    public String getJavaImage() {
        return javaImage;
    }

    public void setJavaImage(String javaImage) {
        this.javaImage = javaImage;
    }

    public String getPythonImage() {
        return pythonImage;
    }

    public void setPythonImage(String pythonImage) {
        this.pythonImage = pythonImage;
    }

    public String getCImage() {
        return cImage;
    }

    public void setCImage(String cImage) {
        this.cImage = cImage;
    }

    public String getCppImage() {
        return cppImage;
    }

    public void setCppImage(String cppImage) {
        this.cppImage = cppImage;
    }

    public int getMaxCodeLength() {
        return maxCodeLength;
    }

    public void setMaxCodeLength(int maxCodeLength) {
        this.maxCodeLength = maxCodeLength;
    }

    public int getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public void setMemoryLimitMb(int memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getSessionTtlHours() {
        return sessionTtlHours;
    }

    public void setSessionTtlHours(int sessionTtlHours) {
        this.sessionTtlHours = sessionTtlHours;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public static class RateLimit {
        private int capacity = 8;
        private int refillPerMinute = 12;
        private int slidingWindowLimit = 20;
        private int slidingWindowSeconds = 60;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerMinute() {
            return refillPerMinute;
        }

        public void setRefillPerMinute(int refillPerMinute) {
            this.refillPerMinute = refillPerMinute;
        }

        public int getSlidingWindowLimit() {
            return slidingWindowLimit;
        }

        public void setSlidingWindowLimit(int slidingWindowLimit) {
            this.slidingWindowLimit = slidingWindowLimit;
        }

        public int getSlidingWindowSeconds() {
            return slidingWindowSeconds;
        }

        public void setSlidingWindowSeconds(int slidingWindowSeconds) {
            this.slidingWindowSeconds = slidingWindowSeconds;
        }
    }
}
