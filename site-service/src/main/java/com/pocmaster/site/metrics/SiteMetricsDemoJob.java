package com.pocmaster.site.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SiteMetricsDemoJob {

    private static final Logger log = LoggerFactory.getLogger(SiteMetricsDemoJob.class);

    private final Counter executions;
    private final AtomicInteger lastBatchSize = new AtomicInteger(0);

    public SiteMetricsDemoJob(MeterRegistry meterRegistry) {
        this.executions = Counter.builder("site_demo_job_executions_total")
                .description("Executions du job demo metriques site-service")
                .tag("service", "site-service")
                .register(meterRegistry);

        Gauge.builder("site_demo_job_last_batch_size", lastBatchSize, AtomicInteger::get)
                .description("Derniere taille de batch simulee")
                .tag("service", "site-service")
                .register(meterRegistry);
    }

    @Scheduled(fixedRate = 30000)
    public void run() {
        int batchSize = ThreadLocalRandom.current().nextInt(1, 20);
        lastBatchSize.set(batchSize);
        executions.increment();
        log.info("site-service metrics demo job executed, batchSize={}", batchSize);
    }
}
