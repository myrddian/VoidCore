package io.aeyer.voidcore.workers;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voidcore.worker")
public class WorkerPoolProperties {

    private PoolConfig llm = new PoolConfig(1);
    private int shutdownTimeoutSeconds = 30;

    public PoolConfig getLlm() { return llm; }
    public void setLlm(PoolConfig llm) { this.llm = llm; }

    public int getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public static class PoolConfig {
        private int poolSize;

        public PoolConfig() {}
        public PoolConfig(int poolSize) { this.poolSize = poolSize; }

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    }
}
