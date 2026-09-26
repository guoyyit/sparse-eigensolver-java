package com.github.guoyyit.sparseeigensolverjava;

import com.github.guoyyit.sparseeigensolverjava.service.EigenTaskManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /** Resume tasks interrupted by a previous shutdown, from their snapshots. */
    @Bean
    ApplicationRunner recoverOnStartup(EigenTaskManager taskManager) {
        return args -> taskManager.recoverInterruptedTasks();
    }
}
