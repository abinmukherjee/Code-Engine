package com.distributedjudge.config;

public final class KafkaTopics {
    public static final String SUBMISSIONS = "submissions";
    public static final String SUBMISSIONS_DLQ = "submissions-dlq";
    public static final String CONSUMER_GROUP = "judge-workers";

    private KafkaTopics() {
    }
}
