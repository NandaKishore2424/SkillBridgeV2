package com.skillbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.skillbridge")
public class SkillbridgeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SkillbridgeBackendApplication.class, args);
	}

}

