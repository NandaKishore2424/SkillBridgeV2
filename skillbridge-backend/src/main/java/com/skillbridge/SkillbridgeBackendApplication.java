package com.skillbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * There is deliberately no {@code @EnableJpaRepositories} here.
 *
 * <p>It used to carry {@code @EnableJpaRepositories(basePackages = "com.skillbridge")},
 * which was redundant twice over: this class lives in {@code com.skillbridge},
 * and Spring Boot's {@code JpaRepositoriesAutoConfiguration} already scans the
 * application class's own package tree. It found the same 24 repositories either
 * way.
 *
 * <p>It was not harmless. An explicit {@code @Enable...} on the application class
 * is processed by every test that uses this class as its configuration —
 * including {@code @WebMvcTest}, whose whole purpose is to load the web layer and
 * nothing else. Boot's type filters exclude the auto-configuration; they cannot
 * exclude an annotation somebody wrote by hand. So every controller slice
 * bootstrapped 24 JPA repositories, then failed with
 * {@code No bean named 'entityManagerFactory' available} — which reads like a
 * missing database and is really a missing exclusion.
 *
 * <p>The general form: <b>an explicit {@code @Enable} that merely restates what
 * auto-configuration would have done is not neutral.</b> It costs you the slice
 * tests, and the error names the wrong thing.
 */
@SpringBootApplication
public class SkillbridgeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SkillbridgeBackendApplication.class, args);
	}

}
