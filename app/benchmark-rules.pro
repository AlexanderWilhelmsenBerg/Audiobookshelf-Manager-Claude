# PRODUCT_SPEC 17.3 — R8 rules that apply to the `benchmark` build type only.
#
# The benchmark variant is minified exactly like release, because a measurement of an unshrunk build is a
# measurement of a build nobody ships. That leaves one thing R8 is right to remove and this build needs:
# the seeding receiver, which no application code calls. Its only caller is `am broadcast` from the shell,
# and R8 cannot see that.
#
# Scoped to the receiver and the class it drives, rather than keeping the fixture package wholesale, so
# that everything else in the benchmark variant is shrunk the way the shipped application is.
-keep class com.example.shelfplayer.benchmarkfixture.BenchmarkFixtureReceiver { *; }
-keep class com.example.shelfplayer.benchmarkfixture.BenchmarkLibrarySeeder { *; }

# Hilt generates a `Hilt_`-prefixed superclass for an `@AndroidEntryPoint` receiver and reaches it by
# name. Keeping the receiver alone leaves the generated half to be renamed, which fails at injection time
# rather than at build time.
-keep class com.example.shelfplayer.benchmarkfixture.Hilt_BenchmarkFixtureReceiver { *; }
