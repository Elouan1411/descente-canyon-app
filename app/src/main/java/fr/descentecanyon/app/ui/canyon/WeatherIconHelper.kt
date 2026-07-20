package fr.descentecanyon.app.ui.canyon

object WeatherIconHelper {
    fun getEmojiForWeatherCode(weatherCode: Int?): String {
        return when (weatherCode) {
            0 -> "☀️" // Clear sky
            1, 2, 3 -> "⛅" // Mainly clear, partly cloudy, and overcast
            45, 48 -> "🌫️" // Fog
            51, 53, 55, 56, 57 -> "🌦️" // Drizzle
            61, 63, 65, 66, 67 -> "🌧️" // Rain
            71, 73, 75, 77 -> "❄️" // Snow
            80, 81, 82 -> "🌧️" // Showers
            85, 86 -> "🌨️" // Snow showers
            95, 96, 99 -> "⛈️" // Thunderstorm
            else -> "❓"
        }
    }

    fun getDescriptionForWeatherCode(weatherCode: Int?): String {
        return when (weatherCode) {
            0 -> "Ciel clair"
            1, 2, 3 -> "Nuageux"
            45, 48 -> "Brouillard"
            51, 53, 55, 56, 57 -> "Bruine"
            61, 63, 65, 66, 67 -> "Pluie"
            71, 73, 75, 77 -> "Neige"
            80, 81, 82 -> "Averses"
            85, 86 -> "Averses de neige"
            95, 96, 99 -> "Orage"
            else -> "Inconnu"
        }
    }
}
