@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PRODUCT_SPEC 16.1: repositories restricted to approved sources.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "shelfplayer"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// PRODUCT_SPEC 9.2 / ADR-0002: Phase 0 keeps core, data, domain and playback boundaries as real
// Gradle modules and hosts `feature:*` code as packages inside `:app`.
include(":app")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":core:model")
include(":core:network")
include(":core:testing")
include(":data:auth")
include(":data:downloads")
include(":data:library")
include(":data:settings")
include(":domain")
// PRODUCT_SPEC 9.2 / PLAY-001 — the media service, added in Phase 2 wave 1. It is the only module that
// may name ExoPlayer or a `MediaSession`, which is what makes "one player, one session" structural.
include(":playback")
