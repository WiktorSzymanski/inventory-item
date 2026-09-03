import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "4.0.6"
}

group = "pl.szymanski.wiktor"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // The Axon MongoDB extension: MongoEventStorageEngine, MongoTokenStore, MongoSagaStore.
    // 4.11.1 is the release that pairs with Axon 4.11.2 above. It depends on mongodb-driver-sync
    // ONLY -- it drags in no Spring Data types -- which is what lets it coexist with whatever
    // Spring Data MongoDB version the Boot BOM picks. See MongoStoreConfig for why the template
    // is hand-written rather than taken from axon-mongo-spring-boot-starter.
    implementation("org.axonframework.extensions.mongo:axon-mongo:4.11.1")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.0.4")
    implementation("org.axonframework:axon-spring-boot-starter:4.11.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // Backs PessimisticCachingRepository's confirmed-state cache. Version comes from the
    // Spring Boot BOM above, so it stays in step with whatever Boot is tested against.
    implementation("com.github.ben-manes.caffeine:caffeine")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.testcontainers:mongodb:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.axonframework:axon-test:4.11.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
