package com.lagradost.quicknovel.ui.updates

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.ItemUpdateNovelBinding
import com.lagradost.quicknovel.ui.BaseDiffCallback
import com.lagradost.quicknovel.ui.NoStateAdapter
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.ui.updates.data.WatchEntry
import com.lagradost.quicknovel.util.UIHelper.setImage

class UpdatesAdapter(
    private val onCheckClick: (WatchEntry) -> Unit,
    private val onRemoveClick: (WatchEntry) -> Unit,
    private val onMarkSeenClick: (WatchEntry) -> Unit,
    private val onCardClick: (WatchEntry) -> Unit,
) : NoStateAdapter<UpdatesAdapter.UiEntry>(
    BaseDiffCallback(
        itemSame = { a, b -> a.entry.novelId == b.entry.novelId },
        contentSame = { a, b ->
            a.isChecking == b.isChecking &&
            a.entry.lastCheckedChapters == b.entry.lastCheckedChapters &&
            a.entry.checkFailed == b.entry.checkFailed &&
            a.entry.baselineChapters == b.entry.baselineChapters
        }
    )
){
    data class UiEntry(val entry: WatchEntry, val isChecking: Boolean)
    class VH(val binding: ItemUpdateNovelBinding) : ViewHolderState<Any>(binding)

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return VH(ItemUpdateNovelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: UiEntry, position: Int) {
        val entry = item.entry
        val isChecking = item.isChecking

        (holder as VH).binding.apply {
            if (novelCover.tag != entry.posterUrl) {
                novelCover.setImage(entry.posterUrl)
                novelCover.tag = entry.novelName
            }
            novelTitle.text = entry.novelName

            // Chapter status text
            val newStatus = when {
                isChecking         -> root.context.getString(R.string.updates_checking)
                entry.neverChecked -> root.context.getString(R.string.updates_not_checked)
                entry.checkFailed  -> root.context.getString(R.string.updates_check_failed)
                entry.hasUpdate    ->
                    root.context.getString(R.string.updates_new_chapters, entry.newChapters, entry.lastCheckedChapters)
                else               ->
                    root.context.getString(R.string.updates_up_to_date, entry.lastCheckedChapters)
            }
            if (statusText.text != newStatus) {
                statusText.text = newStatus
            }

            val hasUpdate = entry.hasUpdate && !isChecking

            // New chapters badge
            newBadge.isVisible = hasUpdate
            if (entry.hasUpdate) newBadge.text = "+${entry.newChapters}"

            // "Mark as seen" chip (only if there are updates)
            markSeenBtn.isVisible = hasUpdate
            markSeenBtn.setOnClickListener { onMarkSeenClick(entry) }

            // Spinner or sync button
            checkProgress.isVisible = isChecking
            checkBtn.isVisible      = !isChecking
            checkBtn.setOnClickListener { onCheckClick(entry) }

            removeBtn.setOnClickListener { onRemoveClick(entry) }
            root.setOnClickListener { onCardClick(entry) }
        }
    }
    override fun onClearView(holder: ViewHolderState<Any>) {
        clearImage((holder as VH).binding.novelCover)
    }
}