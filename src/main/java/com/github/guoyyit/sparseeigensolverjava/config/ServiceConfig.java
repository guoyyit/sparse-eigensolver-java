package com.github.guoyyit.sparseeigensolverjava.config;

import com.github.guoyyit.sparseeigensolverjava.service.FileSnapshotStore;
import com.github.guoyyit.sparseeigensolverjava.service.SnapshotStore;
import com.github.guoyyit.sparseeigensolverjava.service.ThreadPoolSolverExecutor;
import com.github.guoyyit.sparseeigensolverjava.service.SolverExecutor;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires infrastructure beans derived from {@link SolverProperties}. */
@Configuration
@EnableConfigurationProperties(SolverProperties.class)
public class ServiceConfig {

    @Bean
    public SnapshotStore snapshotStore(SolverProperties props) {
        return new FileSnapshotStore(Path.of(props.getSnapshotDir()));
    }

    @Bean
    public SolverExecutor solverExecutor(SolverProperties props) {
        return new ThreadPoolSolverExecutor(props);
    }
}
