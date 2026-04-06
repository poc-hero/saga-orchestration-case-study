package com.pocmaster.site;

import com.lib.pocmaster.saga.config.EnableSagaExecutor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableSagaExecutor
@EnableScheduling
public class SiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiteApplication.class, args);
    }
}
