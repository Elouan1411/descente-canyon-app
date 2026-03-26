package fr.descentecanyon.app.e2e.debit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

class OfflineDebitSyncE2ETest : BaseE2eTest() {
    @Test
    fun offlineDebitIsQueuedThenSyncedWhenConnectivityReturns() {
        relaunchWithConnectivity(isOnline = false)

        waitForTag(TestTags.homeQuickSearch)
        composeRule.onNodeWithTag(TestTags.homeQuickSearch, useUnmergedTree = true).performClick()

        waitForTag(TestTags.searchQueryField)
        composeRule.onNodeWithTag(TestTags.searchQueryField, useUnmergedTree = true)
            .performTextInput("Riolan")

        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.detailReportDebitButton)
        composeRule.onNodeWithTag(TestTags.detailReportDebitButton, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.debitObserverNameField)
        composeRule.onNodeWithTag(TestTags.debitObserverNameField, useUnmergedTree = true)
            .performTextInput("Testeur")
        composeRule.onNodeWithTag(TestTags.debitObserverEmailField, useUnmergedTree = true)
            .performTextInput("test@example.com")
        composeRule.onNodeWithTag(TestTags.debitCommentField, useUnmergedTree = true)
            .performTextInput("Observation E2E")
        composeRule.onNodeWithTag(TestTags.debitSubmitButton, useUnmergedTree = true)
            .performScrollTo()
        composeRule.onNodeWithTag(TestTags.debitSubmitButton, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.detailReportDebitButton)
        composeRule.onNodeWithTag(TestTags.detailReportDebitButton, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.debitPendingCount)
        composeRule.onNodeWithTag(TestTags.debitPendingCount, useUnmergedTree = true)
            .assertIsDisplayed()

        fakeConnectivityObserver.setOnline(true)

        waitForTagToDisappear(TestTags.debitPendingCount)
        composeRule.onNodeWithTag(TestTags.debitSubmitButton, useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
