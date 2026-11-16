package com.libreplayer.ui

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
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.libreplayer.R
import com.libreplayer.app.MainActivity
import com.libreplayer.util.audioReadPermission
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
    fun fourTabsRemainReachableAndRadioDoesNotPretendToStream() {
        selectedItem("Home").assertIsSelected()
        selectedItem("Suche").performClick().assertIsSelected()
        selectedItem("Radio").performClick().assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.discovery_radio_label)).assertIsDisplayed()
        selectedItem("Bibliothek").performClick().assertIsSelected()
        selectedItem("Home").performClick().assertIsSelected()
    }

    @Test
    fun librarySectionSurvivesHomeAndRadioShortcuts() {
        selectedItem("Bibliothek").performClick()
        selectedItem("Alben").performClick().assertIsSelected()
        selectedItem("Home").performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.discovery_open_library)).onFirst().performClick()
        selectedItem("Alben").assertIsSelected()
        selectedItem("Radio").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.discovery_open_library)).performClick()
        selectedItem("Alben").assertIsSelected()
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
