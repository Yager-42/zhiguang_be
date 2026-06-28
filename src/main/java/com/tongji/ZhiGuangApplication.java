package com.tongji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZhiGuangApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhiGuangApplication.class, args);
    }
}
