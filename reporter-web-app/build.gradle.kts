plugins {
    kotlin("js")
}

repositories {
    jcenter()
}

kotlin {
    target {
        browser()
    }

    sourceSets["main"].dependencies {
        implementation(kotlin("stdlib-js"))
        implementation(npm("react", "^16.8.1"))
    }
}
