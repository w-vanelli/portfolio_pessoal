package com.wvanelli.ledgerstream.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ledgerstream.reconciliation")
public class ReconciliationProperties {
    private boolean enabled = true;
    private long orphanGracePeriodSeconds = 900;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getOrphanGracePeriodSeconds() { return orphanGracePeriodSeconds; }
    public void setOrphanGracePeriodSeconds(long seconds) {
        if (seconds < 0) throw new IllegalArgumentException("Grace period must not be negative");
        orphanGracePeriodSeconds = seconds;
    }
}
