package com.gsc.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** Real (wall) time: drives link-layer timers. Orbital time comes from SimulationClock. */
    @Bean
    public Clock wallClock() {
        return Clock.systemUTC();
    }
}
