plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.asm)
    implementation(libs.gdx.tools) {
        exclude("com.badlogicgames.gdx", "gdx-backend-lwjgl")
    }
    testImplementation(libs.junit)
}
