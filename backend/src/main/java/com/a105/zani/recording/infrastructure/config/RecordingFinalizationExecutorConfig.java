package com.a105.zani.recording.infrastructure.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** MP4 합성은 CPU·I/O가 크므로 라이브 수업의 공유 scheduler와 분리하고 한 세션씩 실행한다. */
@Configuration
public class RecordingFinalizationExecutorConfig {

    public static final String EXECUTOR = "recordingFinalizationExecutor";

    @Bean(EXECUTOR)
    public Executor recordingFinalizationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("recording-finalize-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
