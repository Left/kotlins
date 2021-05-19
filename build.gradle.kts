
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
    id("com.google.protobuf") version "0.8.10"
    id("base")
    idea
    application
    `maven-publish`
    kotlin("jvm") version "1.5.0"
    kotlin("plugin.serialization") version "1.5.0"

    // id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

apply(plugin="java")

repositories {
    jcenter()
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
    }
    maven {
        url = uri("https://kotlin.bintray.com/kotlinx")
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.2.1")

    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:1.4.20")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.4.20")

    implementation("com.google.protobuf:protobuf-java:3.12.1")

    protobuf(files("protocol/"))
}

/*
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
 */

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

idea {
    module {
        sourceDirs.add(file("${projectDir}/build/generated/main/java"))
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

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

compileKotlin.kotlinOptions.jvmTarget = "1.8"
compileKotlin.kotlinOptions.freeCompilerArgs = listOf("-Xinline-classes")

// compileKotlin.kotlinOptions.apiVersion = "1.8"

group = "org.vrk"
version = "1.0-SNAPSHOT"
description = "kotlin home server"

/*
nexusPublishing {
    repositories {
        create("myNexus") {
            nexusUrl.set(uri("https://maven.pkg.github.com/vridosh/kotlins"))
            // snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))

            username.set(System.getenv("GITHUB_ACTOR"))
            password.set(System.getenv("GITHUB_TOKEN"))
        }
    }
}
 */

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/vridosh/kotlins")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}