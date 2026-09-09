package com.dev.Tvivo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dev.Tvivo.ui.browse.ResumePrompt
import com.dev.Tvivo.ui.common.IconPill
import com.dev.Tvivo.ui.common.TvivoImageLoader
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * The pre-run page. Activating a card opens this rather than starting a stream.
 *
 * **Why a page and not a straight play.** Activating a card used to start a stream
 * immediately, which makes the grid a minefield: on a 10-foot UI with `max_connections`
 * of 1, a mis-aimed OK press takes the one connection the account has and the only way
 * back is to stop it again. It also left the panel's own metadata — the description it
 * sends on every row — with nowhere to be shown, and left favouriting hidden behind a
 * long-press that nothing advertises.
 *
 * **Play takes focus on arrival**, so the fast path is still two presses (OK, OK) and
 * the page costs a confirmation rather than a decision.
 *
 * Everything on it is a Room read — see [ItemDetailViewModel]. It renders with the panel
 * unreachable.
 */
@Composable
fun ItemDetailScreen(
    contentType: ContentType,
    itemId: Int,
    onPlay: (resumeFromMs: Long, extension: String?) -> Unit,
    onOpenEpisodes: () -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val viewModel: ItemDetailViewModel = viewModel(
        key = "detail:${contentType.name}:$itemId",
        factory = viewModelFactory {
            initializer { ItemDetailViewModel(application, contentType, itemId) }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }
    var resumePrompt by remember { mutableStateOf(false) }

    val isSeries = contentType == ContentType.SERIES

    // Q-25's lesson, applied on the way in rather than after a bug report: a screen that
    // opens with no focus owner has no D-pad behaviour at all. Keyed on the title
    // arriving, because the button is not composed until there is something to show.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) runCatching { playFocus.requestFocus() }
    }

    if (state.notFound) {
        Box(
            modifier = Modifier.fillMaxSize().background(Palette.Bg),
            contentAlignment = Alignment.Center
        ) {
            // Reachable in one real way: a favourite whose row the panel has since
            // dropped. Saying so is better than an empty page that looks broken.
            Text(
                text = "This item is no longer in the catalog.",
                color = Palette.Dim,
                style = TvType.body
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = 96.dp, vertical = 64.dp)
    ) {
        // The poster at its true 2:3, large: this is the one screen with room for the
        // art the grid can only show at 220x330.
        Box(
            modifier = Modifier
                .width(POSTER_WIDTH)
                .height(POSTER_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Elevated)
        ) {
            state.imageUrl?.let { url ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).build(),
                    imageLoader = TvivoImageLoader.get(context),
                    contentDescription = null,
                    // A channel logo letterboxes; a poster crops. Same rule as the grid,
                    // and for the same reason — cropping a logo destroys it.
                    contentScale = if (contentType == ContentType.LIVE) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.width(48.dp))

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = state.title,
                color = Palette.Ink,
                style = TvType.display,
                // Never ellipsised to one line: this panel's titles carry the year and
                // the disambiguating half is at the end.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // The app's one global-action control, not a bespoke button: icon at
                // rest, icon + label on focus, everywhere. Play carries focus on arrival
                // so its label is the one already showing when the page appears.
                IconPill(
                    icon = Icons.Default.PlayArrow,
                    // A show is not playable — `series_id` addresses no stream endpoint
                    // — so the primary action opens the picker and says so.
                    label = if (isSeries) {
                        "View episodes"
                    } else if (state.resumeFromMs != null) {
                        "Resume"
                    } else {
                        "Play"
                    },
                    modifier = Modifier.focusRequester(playFocus),
                    onClick = {
                        when {
                            isSeries -> onOpenEpisodes()
                            state.resumeFromMs != null -> resumePrompt = true
                            else -> onPlay(0L, state.extension)
                        }
                    }
                )

                Spacer(Modifier.width(16.dp))

                IconPill(
                    // Filled when it is one, outlined when it is not. This is the only
                    // pill in the app whose *icon* carries state, and it has to: at rest
                    // the glyph is all there is, so an outline-only heart would never say
                    // whether the item is already a favourite.
                    icon = if (state.isFavourite) {
                        Icons.Default.Favorite
                    } else {
                        Icons.Default.FavoriteBorder
                    },
                    label = if (state.isFavourite) "In favourites" else "Add to favourites",
                    onClick = viewModel::toggleFavourite
                )
            }

            Spacer(Modifier.height(28.dp))

            state.plot?.let { plot ->
                Text(
                    text = plot,
                    color = Palette.Dim,
                    style = TvType.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Scrolls rather than truncates: a synopsis clipped mid-sentence
                        // is worse than one the user can reach the end of. Not focusable,
                        // so it never becomes a D-pad stop between Play and the rail.
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }

    if (resumePrompt) {
        val ms = state.resumeFromMs ?: 0L
        ResumePrompt(
            title = state.title,
            positionMs = ms,
            onResume = {
                resumePrompt = false
                onPlay(ms, state.extension)
            },
            onStartOver = {
                resumePrompt = false
                viewModel.clearResume()
                onPlay(0L, state.extension)
            },
            onDismiss = { resumePrompt = false }
        )
    }
}

/** 2:3 at roughly twice the card, which is the point of the screen. */
private val POSTER_WIDTH = 300.dp
private val POSTER_HEIGHT = 450.dp
