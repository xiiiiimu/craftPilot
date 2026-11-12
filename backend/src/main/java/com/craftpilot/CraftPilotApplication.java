package com.craftpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class CraftPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(CraftPilotApplication.class, args);
    }
}
