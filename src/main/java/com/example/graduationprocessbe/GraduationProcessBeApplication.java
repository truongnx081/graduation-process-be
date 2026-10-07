package com.example.graduationprocessbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GraduationProcessBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraduationProcessBeApplication.class, args);
    }

}
