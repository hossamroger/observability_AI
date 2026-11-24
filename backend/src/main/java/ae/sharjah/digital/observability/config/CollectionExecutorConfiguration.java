package ae.sharjah.digital.observability.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.DefaultManagedTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Local development owns bounded workers; WebLogic delegates workers to the container. */
@Configuration
@ConditionalOnProperty(name="monitoring.collector.enabled", havingValue="true")
public class CollectionExecutorConfiguration {
    @Bean(name="monitoringCollectionExecutor")
    @Profile("!weblogic")
    public ThreadPoolTaskExecutor localCollectionExecutor(@Value("${monitoring.collection.worker-count:4}") int workers) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, Math.min(16, workers));
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(size);
        executor.setThreadNamePrefix("obs-collector-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(name="monitoringCollectionExecutor")
    @Profile("weblogic")
    public DefaultManagedTaskExecutor managedCollectionExecutor(
            @Value("${monitoring.collection.managed-executor-jndi:java:comp/DefaultManagedExecutorService}") String jndiName) {
        DefaultManagedTaskExecutor executor = new DefaultManagedTaskExecutor();
        executor.setJndiName(jndiName);
        return executor;
    }
}
