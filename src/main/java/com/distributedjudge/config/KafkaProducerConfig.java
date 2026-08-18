package com.distributedjudge.config;

import com.distributedjudge.dto.SubmissionMessage;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
@Profile("!worker")
public class KafkaProducerConfig {
    @Bean
    ProducerFactory<String, SubmissionMessage> submissionProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        );
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, SubmissionMessage> submissionKafkaTemplate(ProducerFactory<String, SubmissionMessage> submissionProducerFactory) {
        return new KafkaTemplate<>(submissionProducerFactory);
    }

    @Bean
    KafkaAdmin.NewTopics judgeTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(KafkaTopics.SUBMISSIONS).partitions(6).replicas(1).build(),
                TopicBuilder.name(KafkaTopics.SUBMISSIONS_DLQ).partitions(6).replicas(1).build()
        );
    }
}
