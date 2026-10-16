plugins {
    java
    application
}
repositories { mavenCentral() }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("dev.wissam.sshterm.pq.LocalProbe") }
tasks.test { useJUnit() }
