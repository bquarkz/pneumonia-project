plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.pneumonia"
version = "0.0.1-SNAPSHOT"
description = ""

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

val springModulithVersion = "2.1.1"

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-amqp")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")

	implementation("org.apache.commons:commons-lang3")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	runtimeOnly("org.postgresql:postgresql")

	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	// Spring Modulith: module boundaries, event publication registry (outbox) and
	// AMQP event externalization. See docs/DECISIONS.md for why.
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	implementation("org.springframework.modulith:spring-modulith-events-api")
	implementation("org.springframework.modulith:spring-modulith-events-amqp")
	implementation("org.springframework.modulith:spring-modulith-events-jdbc")
	// Genuinely missing piece: the event publication registry needs an EventSerializer
	// bean to construct its repository (constructor injection, no default). Without
	// this, the app context fails to start. This registers a Jackson-3-based
	// EventSerializer used ONLY for the durable event publication registry's
	// `serialized_event` column - unrelated to (and does not replace) the AMQP
	// wire-format JSON conversion, which spring-modulith-events-amqp already wires onto
	// RabbitTemplate via its own RabbitTemplateCustomizer.
	implementation("org.springframework.modulith:spring-modulith-events-jackson")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-amqp-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")

	// Genuinely missing: no in-memory database was on the classpath. Used only by the
	// @ApplicationModuleTest slices, in PostgreSQL compatibility mode (see
	// src/test/resources/application.yml) so the real Flyway migrations - authoritative
	// for schema in every environment, including this one - apply unmodified. Never
	// used at runtime, where Postgres itself is authoritative.
	testRuntimeOnly("com.h2database:h2")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:$springModulithVersion")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Maven's spring-boot-maven-plugin repackages in place, leaving a single jar under target/.
// Gradle's Boot plugin keeps the plain (non-executable) jar alongside bootJar's output by
// default - disable it so build/libs/ also holds exactly one jar.
tasks.named<Jar>("jar") {
	enabled = false
}
