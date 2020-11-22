import com.google.protobuf.gradle.generateProtoTasks
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.google.protobuf:protobuf-gradle-plugin:0.8.14")
    }
}

plugins {
    id("base")
    `application`
    kotlin("jvm") version "1.4.10"
    id("com.google.protobuf") version "0.8.8"
}

apply(plugin="java")

repositories {
    jcenter()
    mavenLocal()
    maven {
        url = uri("https://jitpack.io")
    }

    maven {
        url = uri("https://kotlin.bintray.com/kotlinx")
    }

    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

protobuf {
    protoc {
        // The artifact spec for the Protobuf Compiler
        artifact = "com.google.protobuf:protoc:3.12.1"
    }
    generateProtoTasks {
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.4.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.3.7")
    implementation("io.vertx:vertx-web:3.8.5")
    implementation("io.vertx:vertx-lang-kotlin:3.8.5")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:3.8.5")
    implementation("io.lettuce:lettuce-core:5.1.8.RELEASE")
    implementation("com.google.guava:guava:28.2-jre")
    implementation("com.github.vidstige:jadb:v1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.0.1")

    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:1.4.20")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.4.20")

    implementation("com.google.protobuf:protobuf-java:3.12.1")
    protobuf(files("../NodeServer/protocol/"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets {
    main {
        java.srcDir("src/main/java")
    }
    test {
        java.srcDir("src/test/java")
    }
}

kotlin {
    sourceSets["main"].apply {
        kotlin.srcDir("src/main/java")
    }
}

tasks.withType<Jar>() {
    configurations["compileClasspath"].forEach { file: File ->
        println(file)
        from(zipTree(file.absoluteFile))
    }
}

application {
    mainClass.set("RunKt")
}

val compileKotlin: org.jetbrains.kotlin.gradle.tasks.KotlinCompile by tasks

compileKotlin.kotlinOptions.jvmTarget = "1.8"
// compileKotlin.kotlinOptions.apiVersion = "1.8"

group = "org.vrk"
version = "1.0-SNAPSHOT"
description = "kotlin home server"
