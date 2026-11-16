package com.libreplayer.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.libreplayer.R
import com.libreplayer.app.MainActivity
import com.libreplayer.util.audioReadPermission
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(audioReadPermission())

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun fourTabsRemainReachableAndRadioExplainsNetworkUse() {
        selectedItem("Home").assertIsSelected()
        selectedItem("Suche").performClick().assertIsSelected()
        selectedItem("Radio").performClick().assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.radio_privacy)).assertIsDisplayed()
        selectedItem("Bibliothek").performClick().assertIsSelected()
        selectedItem("Home").performClick().assertIsSelected()
    }

    @Test
    fun librarySectionSurvivesHomeShortcutAndRadioTab() {
        var stage = "initial Library"
        try {
            selectedItem("Bibliothek").performClick().assertIsSelected()
            stage = "select Alben"
            selectedItem("Alben").performClick().assertIsSelected()
            stage = "return Home"
            selectedItem("Home").performClick().assertIsSelected()
            stage = "Home Library shortcut"
            compose.onAllNodesWithText(compose.activity.getString(R.string.discovery_open_library))
                .onFirst().performScrollTo().assertIsDisplayed().performClick()
            stage = "Library restored after Home shortcut"
            selectedItem("Bibliothek").assertIsSelected()
            selectedItem("Alben").assertIsSelected()
            stage = "open Radio"
            selectedItem("Radio").performClick().assertIsSelected()
            stage = "Library restored after Radio"
            selectedItem("Bibliothek").performClick().assertIsSelected()
            selectedItem("Alben").assertIsSelected()
        } catch (failure: Throwable) {
            Log.e("NavigationTest", "Navigation failure at: $stage", failure)
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("NavigationTest") }
                .onFailure { Log.e("NavigationTest", "Could not dump semantics", it) }
            runCatching {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val file = File(
                        instrumentation.targetContext.getExternalFilesDir(null),
                        "navigation-library-restoration-failure.png",
                    )
                    file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Log.e("NavigationTest", "Failure screenshot: ${file.absolutePath}")
                } finally {
                    screenshot.recycle()
                }
            }.onFailure { Log.e("NavigationTest", "Could not capture screenshot", it) }
            throw failure
        }
    }

    @Test
    fun settingsOffersVisibleReturnToHome() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.discovery_settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.plus_back)).performClick()
        selectedItem("Home").assertIsSelected()
    }

    private fun selectedItem(text: String) = compose.onNode(
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
    )
}
