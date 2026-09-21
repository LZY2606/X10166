plugins {
    application
}

group = "dev.lockmerge"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.lockmerge.Main")
}

tasks.withType<JavaCompile> {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
}
