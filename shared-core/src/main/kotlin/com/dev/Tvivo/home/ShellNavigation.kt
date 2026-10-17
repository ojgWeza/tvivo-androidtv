package com.dev.Tvivo.home

enum class ShellTab { MyTvivo, Movies, Series, Live, Search, Settings }

object ShellNavigation {
    sealed interface BackAction {
        data object ShowExitConfirmation : BackAction
        data object ReturnToMyTvivo : BackAction
    }

    /** The app exits only after the user confirms from My Tvivo's dialog. */
    fun backFromTopLevel(tab: ShellTab): BackAction =
        if (tab == ShellTab.MyTvivo) BackAction.ShowExitConfirmation
        else BackAction.ReturnToMyTvivo
}
