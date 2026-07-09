package com.lagradost.quicknovel.ui.updates

import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.FragmentUpdatesBinding
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.ui.BaseFragment
import com.lagradost.quicknovel.ui.updates.data.WatchEntry
import com.lagradost.quicknovel.ui.updates.ui.AddWatchBottomSheet
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar

class UpdatesFragment : BaseFragment<FragmentUpdatesBinding>(
    BindingCreator.Inflate(FragmentUpdatesBinding::inflate)
) {
    private val viewModel: UpdatesViewModel by viewModels()

    override fun fixLayout(view: View) {}

    override fun onBindingCreated(binding: FragmentUpdatesBinding) {
        activity?.fixPaddingStatusbar(binding.updatesToolbar)

        val adapter = UpdatesAdapter(
            onCheckClick    = { viewModel.checkOne(it) },
            onRemoveClick   = { confirmRemove(it) },
            onMarkSeenClick = { viewModel.markAsSeen(it.novelId) },
            onCardClick     = { entry -> loadResult(entry.novelSource, entry.apiName) },
        )

        /**
         * Updates the UI list by filtering only novels that have updates
         * or are currently being checked.
         */
        fun updateUIList(entry: List<WatchEntry>? = null) {
            val allEntries = entry ?: viewModel.entries.value ?: return
            val checkingIds = viewModel.checkingIds.value ?: emptySet()
            val filteredEntries = allEntries.filter { it.hasUpdate || it.novelId in checkingIds || it.checkFailed }

            adapter.submitList(filteredEntries.map {
                UpdatesAdapter.UiEntry(it, it.novelId in checkingIds)
            })
            val isCheckingAnything = checkingIds.isNotEmpty()
            val noUpdatesToShow = filteredEntries.isEmpty()
            binding.apply {
                updatesLoading.isVisible = false
                updatesRecycler.isVisible = filteredEntries.isNotEmpty()
                emptyState.isVisible = noUpdatesToShow && !isCheckingAnything
            }
        }


        // observe list of watching novels
        observe(viewModel.entries) { entries ->
            updateUIList(entries)
        }
        // list of loading novels
        observe(viewModel.checkingIds) { checking ->
            viewModel.entries.value?.let { entries ->
                updateUIList(entries)
            }
            binding.updatesToolbar.menu.findItem(R.id.action_check_all)?.isEnabled = checking.isEmpty()
        }
        observe(viewModel.bookmarkedNovels) { novels ->
            if (novels.isEmpty()) return@observe
            handleOpenBottomSheet(novels)
        }

        binding.apply {
            updatesToolbar.setNavigationOnClickListener {
                dispatchBackPressed()
            }
            updatesRecycler.layoutManager = LinearLayoutManager(requireContext())
            updatesRecycler.adapter = adapter




            //  "Check All" in toolbar
            updatesToolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_check_all -> { viewModel.checkAll(); true }
                    else -> false
                }
            }

            fabAddWatchNovel.setOnClickListener {
                if (viewModel.entries.value == null) return@setOnClickListener
                viewModel.loadBookmarkedNovels()
            }
        }


        ViewCompat.setOnApplyWindowInsetsListener(binding.fabAddWatchNovel) { fab, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            fab.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = navBar.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            insets
        }

    }

    private fun handleOpenBottomSheet(novels: List<ResultCached>) {
        val currentWatchIds = viewModel.entries.value?.map { it.novelId }?.toSet() ?: emptySet()

        if (childFragmentManager.findFragmentByTag("add_watch") != null) return

        AddWatchBottomSheet(
            novels = novels,
            initialIds = currentWatchIds
        ) { selected ->
            selected.forEach { viewModel.addToWatch(it) }
        }.show(childFragmentManager, "add_watch")
    }


    private fun confirmRemove(entry: WatchEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove)
            .setMessage(getString(R.string.updates_remove_confirm, entry.novelName))
            .setPositiveButton(R.string.remove) { _, _ ->
                viewModel.removeFromWatch(entry.novelId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}