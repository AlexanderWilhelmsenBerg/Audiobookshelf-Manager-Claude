plugins {
    id("shelfplayer.jvm.library")
    id("shelfplayer.hilt")
}

dependencies {
    api(projects.core.model)
}

/**
 * PRODUCT_SPEC 17.3 — "security ... policies: 90%".
 *
 * In this phase the security policy is **redaction**: which fields may reach a log and which may not,
 * which is the rule standing between an access token and a pasted bug report. It is enforced here rather
 * than in the root aggregate because a threshold scoped to one package needs a report filter, and a
 * filter belongs to the report of the module that owns the package.
 *
 * Measured at 92.7% when this was wired. The bound is 17.3's number, not that one.
 */
kover {
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
