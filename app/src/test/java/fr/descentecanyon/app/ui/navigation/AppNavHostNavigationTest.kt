package fr.descentecanyon.app.ui.navigation

import androidx.navigation.NavOptionsBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavHostNavigationTest {

    @Test
    fun `secondary navigation enables launch single top`() {
        val builder = NavOptionsBuilder()

        builder.applySingleTopNavigation()

        assertTrue(builder.launchSingleTop)
    }
}
