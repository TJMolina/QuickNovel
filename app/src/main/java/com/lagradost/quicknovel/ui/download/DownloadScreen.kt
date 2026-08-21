package com.lagradost.quicknovel.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.lagradost.quicknovel.compose.ripple
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.BaseSearchBar
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.compose.IsScrolling
import com.lagradost.quicknovel.compose.rounded
import com.lagradost.quicknovel.getLibraries
import com.lagradost.quicknovel.tachiyomi.AndroidPreferenceStore
import com.lagradost.quicknovel.tachiyomi.collectAsState
import com.lagradost.quicknovel.ui.common.HorizontalTab
import com.lagradost.quicknovel.ui.common.ImmutableSearchList
import com.lagradost.quicknovel.ui.common.SearchList
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DownloadScreen(
    state: DownloadPageState,
    action: (DownloadPageAction) -> Unit
) {

    val context = LocalContext.current

    val pagesNames = listOf(
        stringResource(R.string.tab_downloads)
    ).plus(context.getLibraries().map { it.title })
        .toPersistentList()
    val store = AndroidPreferenceStore(context)

    val downloadIsRow = store.getBoolean(stringResource(R.string.download_list_view_key), true)
    val downloadIsRowState by downloadIsRow.collectAsState()

    var fabExpanded by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(snapAnimationSpec = null)
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.padding(bottom = 40.dp),
                onClick = {
                    action(DownloadPageAction.ShowSorting)
                },
                // Elevation actually changes the color, because who wanted a sane framework
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp
                ),
                containerColor = colors.surfaceVariant,
                contentColor = colors.onBackground,
                text = {
                    Text(stringResource(R.string.filter_dialog_sort_by))
                },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort_24dp),
                        contentDescription = stringResource(R.string.filter_dialog_sort_by)
                    )
                },
                expanded = fabExpanded
            )
        },
        topBar = {
            BaseSearchBar(
                content = {
                    Spacer(modifier = Modifier.height(5.dp))
                },
                onQueryChange = { query ->
                    action(DownloadPageAction.Search(query))
                },
                onSearch = { query ->
                    action(DownloadPageAction.Search(query))
                },
                scrollBehavior = scrollBehavior,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search_icon),
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = {
                        downloadIsRow.set(!downloadIsRowState)
                    }) {
                        Icon(
                            painter = painterResource(if (downloadIsRowState) R.drawable.ic_baseline_grid_view_24 else R.drawable.ic_baseline_list_24),
                            contentDescription = stringResource(if (downloadIsRowState) R.string.grid_view else R.string.list_view),
                            modifier = Modifier.size(24.dp),
                            tint = colors.onBackground
                        )
                    }
                },
                placeholder = stringResource(R.string.search_downloads)
            )
        }, modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        if (state.pages.isEmpty() || state.tabNames.isEmpty()) return@Scaffold

        key (state.tabNames) {
            val pagerState = rememberPagerState(
                initialPage = state.activePage.coerceIn(0, state.tabNames.size - 1),
                pageCount = { state.tabNames.size }
            )

        val currentPage = pagerState.currentPage
        LaunchedEffect(currentPage) {
            action(DownloadPageAction.SelectPage(currentPage))
        }

        Column {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    // This fixes the double padding from the bottom nav bar
                    .padding(
                        start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                        end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                        top = innerPadding.calculateTopPadding()
                    )
                    .weight(1.0f)
            ) { page ->
                DownloadRow(
                    isRow = downloadIsRowState,
                    page,
                    state.pages.getOrNull(page) ?: ImmutableSearchList(),
                    action,
                    scrollingChange = { isScrollingUp ->
                        fabExpanded = isScrollingUp
                    })
            }

                HorizontalTab(pagerState, state.tabNames, colors.surfaceVariant)
        }
        }
    }
}

@Composable
fun DownloadRow(
    isRow: Boolean,
    index: Int,
    row: ImmutableSearchList?,
    action: (DownloadPageAction) -> Unit,
    scrollingChange: (Boolean) -> Unit
) {
    if (row == null) return

    val searchAction = remember<(SearchResponseAction) -> Unit>(action) {
        { item ->
            action(DownloadPageAction.ResultAction(item))
        }
    }
    var refreshing by remember { mutableStateOf(false) }
    val lazyGridState: LazyGridState = rememberLazyGridState()
    lazyGridState.IsScrolling(up = {
        scrollingChange(true)
    }, down = {
        scrollingChange(false)
    })

    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            action(DownloadPageAction.Refresh)
            scope.launch {
                delay(200.milliseconds)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        SearchList(
            isRow = isRow,
            state = row,
            searchAction = searchAction,
            modifier = Modifier,
            lazyGridState = lazyGridState,
            footer = if (index == 0) {
                if (isRow) {
                    ::RowFooter
                } else {
                    ::BoxFooter
                }
            } else {
                null
            }
        )
    }
}

@Composable
fun RowFooter() {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .rounded()
            .background(colors.surfaceContainer)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    MainActivity.importEpub()
                },
                onLongClick = {
                }
            )
            .ripple(interactionSource)
    ) {
        Icon(
            modifier = Modifier.size(30.dp),
            painter = painterResource(R.drawable.ic_baseline_add_24),
            contentDescription = stringResource(R.string.import_epub),
            tint = colors.onBackground
        )

        Text(
            modifier = Modifier.padding(start = 15.dp),
            text = stringResource(R.string.import_epub),
            color = colors.onBackground,
            fontSize = 13.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BoxFooter() {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(interactionSource = interactionSource, indication = null, onClick = {
                MainActivity.importEpub()
            })
            .rounded()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .rounded()
                .ripple(interactionSource),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                modifier = Modifier.size(40.dp),
                painter = painterResource(R.drawable.ic_baseline_add_24),
                contentDescription = stringResource(R.string.import_epub),
                tint = colors.onBackground
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.import_epub),
                color = colors.onBackground,
                fontSize = 13.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
@PreviewLightDark
fun DownloadScreenPreview() {
    CloudStreamTheme {
        DownloadScreen(
            state = DownloadPageState(), action = {})
    }
}
