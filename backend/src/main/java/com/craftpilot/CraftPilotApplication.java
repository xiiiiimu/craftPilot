package com.craftpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class CraftPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(CraftPilotApplication.class, args);
    }
}
