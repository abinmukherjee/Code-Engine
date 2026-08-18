package com.distributedjudge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JudgeProperties.class)
public class JudgeConfig {
}
