package dev.hashmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HashmarkApplication {
    public static void main(String[] args) {
        SpringApplication.run(HashmarkApplication.class, args);
    }
}
