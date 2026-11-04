package com.agentoffice.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Clock cho test: mặc định chạy theo giờ thật; set() để đóng băng/nhảy thời gian, reset() để trả về giờ thật. */
public class MutableClock extends Clock {
    private volatile Instant fixed;

    public void set(Instant t) { this.fixed = t; }
    public void advance(java.time.Duration d) { this.fixed = instant().plus(d); }
    public void reset() { this.fixed = null; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { Instant f = fixed; return f != null ? f : Instant.now(); }
}
