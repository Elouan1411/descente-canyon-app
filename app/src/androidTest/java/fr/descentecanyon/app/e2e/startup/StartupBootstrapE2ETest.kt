package fr.descentecanyon.app.e2e.startup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

class StartupBootstrapE2ETest : BaseE2eTest() {
    @Test
    fun coldStartShowsHomeAndLatestDebits() {
        waitForTag(TestTags.homeQuickSearch)

        composeRule.onNodeWithTag(TestTags.homeQuickSearch, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.homeAddCanyon, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Derniers debits")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Riolan")
            .assertIsDisplayed()
    }
}
