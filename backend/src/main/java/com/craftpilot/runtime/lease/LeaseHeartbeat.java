package com.craftpilot.runtime.lease;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps run leases alive while a worker executes them. One shared scheduler for all runs; each run registers a
 * single periodic task that stops on its own when the lease is lost or the deadline passes, and is cancelled when
 * the run's {@link LeaseHandle} is closed.
 */
@Component
public class LeaseHeartbeat implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final ScheduledThreadPoolExecutor scheduler;
    private final Duration ttl;
    private final Duration interval;
    private final Set<LeaseHandle> active = ConcurrentHashMap.newKeySet();

    public LeaseHeartbeat(@Value("${craftpilot.runtime.lease-seconds:30}") long leaseSeconds,
                          @Value("${craftpilot.runtime.lease-renew-interval-ms:0}") long renewIntervalMs,
                          @Value("${craftpilot.runtime.lease-heartbeat-threads:2}") int threads) {
        this.ttl = Duration.ofSeconds(leaseSeconds);
        this.interval = Duration.ofMillis(renewIntervalMs > 0 ? renewIntervalMs : Math.max(1, leaseSeconds * 1000 / 3));
        AtomicInteger seq = new AtomicInteger();
        this.scheduler = new ScheduledThreadPoolExecutor(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "lease-heartbeat-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    /** Starts renewing; the caller must close the handle (try-with-resources) when the run leaves this worker. */
    public LeaseHandle start(String runKey, long epoch, Supplier<RenewOutcome> renewer) {
        LeaseHandle handle = new LeaseHandle(runKey, epoch, ttl, active::remove);
        active.add(handle);
        long period = interval.toMillis();
        handle.attach(scheduler.scheduleWithFixedDelay(() -> beat(handle, renewer), period, period,
                TimeUnit.MILLISECONDS));
        return handle;
    }

    private void beat(LeaseHandle handle, Supplier<RenewOutcome> renewer) {
        if (handle.state() != LeaseHandle.State.ACTIVE) {
            stop(handle);
            return;
        }
        boolean keepGoing;
        try {
            RenewOutcome outcome = renewer.get();
            keepGoing = handle.apply(outcome);
            if (!keepGoing) {
                LOG.info("runtime_lease_heartbeat_stopped run={} epoch={} outcome={}", handle.runKey(), handle.epoch(),
                        outcome);
            }
        } catch (RuntimeException ex) {
            keepGoing = handle.applyError();
            LOG.warn("runtime_lease_renew_error run={} epoch={} type={} lost={}", handle.runKey(), handle.epoch(),
                    ex.getClass().getSimpleName(), !keepGoing);
        }
        if (!keepGoing) {
            stop(handle);
        }
    }

    private void stop(LeaseHandle handle) {
        // Cancel the periodic task but keep the handle registered until the run closes it.
        handle.cancelTask();
    }

    /** Runs currently holding a heartbeat registration; 0 when no run is executing on this worker. */
    public int activeCount() {
        return active.size();
    }

    Duration interval() {
        return interval;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
