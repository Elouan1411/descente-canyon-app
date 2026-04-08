package fr.descentecanyon.app.ui.test

object TestTags {
    const val homeQuickSearch = "home_quick_search"
    const val homeAddCanyon = "home_add_canyon"
    const val searchQueryField = "search_query_field"
    const val detailFavoriteButton = "detail_favorite_button"
    const val detailReportDebitButton = "detail_report_debit_button"
    const val favoritesList = "favorites_list"
    const val debitObserverNameField = "debit_observer_name_field"
    const val debitObserverEmailField = "debit_observer_email_field"
    const val debitObservationDateField = "debit_observation_date_field"
    const val debitCommentField = "debit_comment_field"
    const val debitPendingCount = "debit_pending_count"
    const val debitSubmitButton = "debit_submit_button"

    fun canyonCard(id: Int): String = "canyon_card_$id"
}
