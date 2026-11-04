package com.agentoffice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Thay Clock của TimeConfig bằng bản điều khiển được (xem MutableClock) trong mọi test tích hợp. */
@TestConfiguration
public class TestClockConfig {
    @Bean @Primary
    public MutableClock testClock() { return new MutableClock(); }
}
