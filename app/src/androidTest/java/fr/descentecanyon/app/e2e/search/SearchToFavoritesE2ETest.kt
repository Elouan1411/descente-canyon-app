package fr.descentecanyon.app.e2e.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

class SearchToFavoritesE2ETest : BaseE2eTest() {
    @Test
    fun searchOpenDetailAndPersistFavoriteInFavoritesTab() {
        waitForTag(TestTags.homeQuickSearch)

        composeRule.onNodeWithTag(TestTags.homeQuickSearch, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.searchQueryField)
        composeRule.onNodeWithTag(TestTags.searchQueryField, useUnmergedTree = true)
            .performTextInput("Riolan")

        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.detailFavoriteButton)
        composeRule.onNodeWithTag(TestTags.detailFavoriteButton, useUnmergedTree = true)
            .performClick()

        composeRule.onNodeWithText("Favoris")
            .performClick()

        waitForTag(TestTags.favoritesList)
        composeRule.onNodeWithTag(TestTags.favoritesList, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
