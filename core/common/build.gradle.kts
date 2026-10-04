plugins {
    id("shelfplayer.jvm.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

dependencies {
    api(projects.core.model)
}

/*
 * PRODUCT_SPEC 17.3 — "security ... policies: 90%".
 *
 * In this phase the security policy is **redaction**: which fields may reach a log and which may not,
 * which is the rule standing between an access token and a pasted bug report. It is defined here rather
 * than in the root aggregate because a threshold scoped to one package needs a report filter, and a
 * filter belongs to the report of the module that owns the package.
 *
 * The bound is 17.3's number. Ordinary module/root verification executes this rule (R-125).
 */
kover {
    // The root `gate` variant aggregates this module's JVM classes (see the root build file). This module's
    // own redaction rule below stays on `total`: for a JVM module that is the same single `jvm` variant.
    currentProject {
        createVariant("gate") {
            add("jvm")
        }
    }
    reports {
        total {
            filters {
                includes { classes("com.example.shelfplayer.core.common.log.*") }
                excludes {
                    // Hilt's generated factories, and the sinks that exist to do nothing.
                    classes("*_Factory*", "*NoOp*")
                }
            }
            verify {
                rule("PRODUCT_SPEC 17.3 — redaction policy line coverage") {
                    bound {
                        minValue = 90
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    }
                }
            }
        }
    }
}

// PRODUCT_SPEC 17.3 / R-125: the aggregate 80% rule does not replace this security threshold.
tasks.named("verifyDebug") {
    dependsOn(tasks.named("koverVerify"))
}
