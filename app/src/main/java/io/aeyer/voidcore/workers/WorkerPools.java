package io.aeyer.voidcore.workers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WorkerPools {

    private static final Logger log = LoggerFactory.getLogger(WorkerPools.class);
    private static final String LLM = "llm";

    private final WorkerPoolProperties properties;
    private final Map<String, ExecutorService> pools = new LinkedHashMap<>();

    public WorkerPools(WorkerPoolProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        pools.put(LLM, createPool(LLM, properties.getLlm().getPoolSize()));
    }

    public ExecutorService llmPool() {
        return pools.get(LLM);
    }

    private ExecutorService createPool(String name, int size) {
        int poolSize = Math.max(1, size);
        AtomicInteger counter = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, name + "-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        log.info("Worker pool '{}' initialised with {} threads", name, poolSize);
        return pool;
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<String, ExecutorService> entry : pools.entrySet()) {
            drain(entry.getKey(), entry.getValue());
        }
    }

    private void drain(String name, ExecutorService pool) {
        pool.shutdown();
        try {
            int timeout = properties.getShutdownTimeoutSeconds();
            if (!pool.awaitTermination(timeout, TimeUnit.SECONDS)) {
                int dropped = pool.shutdownNow().size();
                log.warn("Worker pool '{}' did not drain in {}s; forced shutdown, {} tasks dropped",
                        name, timeout, dropped);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker pool '{}' shut down", name);
    }
}
