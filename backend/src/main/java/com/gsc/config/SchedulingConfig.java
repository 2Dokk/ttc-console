package com.gsc.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * All periodic loops (tracking, spacecraft, FOP dispatcher) share Spring's single scheduler thread,
 * so they never run concurrently with each other. Tests disable this and drive the loops by hand.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "gsc.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
