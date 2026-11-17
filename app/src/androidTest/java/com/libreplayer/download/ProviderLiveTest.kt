package com.libreplayer.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.app.MainActivity
import com.libreplayer.data.catalog.CatalogRights
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class ProviderLiveTest {
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val liveRule: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Requires explicit liveProviders=true", InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
                base.evaluate()
            }
        }
    }).around(activityRule)

    @Test
    fun audiusAndCcmixterAndSomaFmRespondOnDevice() = runBlocking(Dispatchers.IO) {
        lateinit var app: LibrePlayerApplication
        activityRule.scenario.onActivity { app = it.application as LibrePlayerApplication }
        withTimeout(90_000) {
            val audius = app.appContainer.catalogRepository.search("lofi", "audius")
            assertTrue("Audius returned no tracks: ${audius.errors}", audius.tracks.isNotEmpty())
            val audiusTrack = audius.tracks.first()
            assertTrue(audiusTrack.providerId == "audius")
            assertTrue(audiusTrack.streamUrl.startsWith("https://api.audius.co/v1/tracks/"))
            assertFalse("All-rights-reserved Audius track must not be downloadable", CatalogRights.isAuthorized(audiusTrack))

            val mixter = app.appContainer.catalogRepository.search("jazz", "ccmixter")
            assertTrue("ccMixter returned no tracks: ${mixter.errors}", mixter.tracks.isNotEmpty())
            val mixterTrack = mixter.tracks.first()
            assertTrue(mixterTrack.providerId == "ccmixter")
            assertTrue(mixterTrack.streamUrl.startsWith("https://ccmixter.org/content/"))

            val soma = app.appContainer.radioRepository.somaFmStations()
            assertTrue("SomaFM returned no stations", soma.isNotEmpty())
            assertTrue(soma.all { it.stationUuid.startsWith("somafm:") })
            val resolved = app.appContainer.radioRepository.resolve(soma.first())
            assertTrue(resolved.streamUrl.startsWith("https://ice"))
            assertTrue(resolved.streamUrl.contains("somafm.com"))
        }
    }
}
