package com.east.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EastApplication {

    public static void main(String[] args) {
        SpringApplication.run(EastApplication.class, args);
    }
}
