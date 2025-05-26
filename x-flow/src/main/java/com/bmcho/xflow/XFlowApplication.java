package com.bmcho.xflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class XFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(XFlowApplication.class, args);
    }

}
