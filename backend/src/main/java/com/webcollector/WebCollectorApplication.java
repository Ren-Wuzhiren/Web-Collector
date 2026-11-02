package com.webcollector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebCollectorApplication.class, args);
    }
}

