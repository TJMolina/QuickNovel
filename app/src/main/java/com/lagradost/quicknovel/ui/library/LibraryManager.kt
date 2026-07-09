package com.lagradost.quicknovel.ui.library

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.DefaultLibrary
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.addLibrary
import com.lagradost.quicknovel.deleteLibrary
import com.lagradost.quicknovel.getLibraries
import com.lagradost.quicknovel.getLibraryBookmarkCount
import com.lagradost.quicknovel.mergeLibraries
import com.lagradost.quicknovel.updateLibrary
import com.lagradost.quicknovel.util.SingleSelectionHelper.showBottomDialog
import androidx.appcompat.app.AlertDialog
import com.lagradost.quicknovel.util.AppUtils.toLibraryKey

object LibraryManager {

    //Refresh the sorted list of libraries after rename, deleting or adding
    fun refreshList(context: Context, adapter: LibrarySectionAdapter) {
        val updatedList = context.getLibraries().sortedBy { it.position }
        adapter.submitList(updatedList)
    }

    //show recyclerview with libraries
    fun showLibraryBottomDialog(
        context: Context,
        list: List<DefaultLibrary>,
        selectedIndex: Int = -1,//Start with a library selected by default
        title: String,
        callback: (Int) -> Unit
    ) {
        //Determines whether to show the icons for editing different attributes.
        var isEditing = false
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(R.layout.bottom_selection_libraries)

        val recyclerView = dialog.findViewById<RecyclerView>(R.id.listview1)!!
        val textView = dialog.findViewById<TextView>(R.id.bottom_selection_libraries_title)!!
        val actionAddButton = dialog.findViewById<TextView>(R.id.actionAdd)!!
        val actionEditButton = dialog.findViewById<ImageButton>(R.id.actionEdit)!!

        //title of dialog
        textView.text = title

        val libraryAdapter = LibrarySectionAdapter(
            selectedIndex,
            onDragFinished = { newList ->
                //Store the new library order internally
                newList.forEachIndexed { index, lib ->
                    context.updateLibrary(lib.copy(position = index + 1))
                }
            },
            onItemClick = { item ->
                //Changes the currently selected library in the ViewModel and saves it locally
                callback.invoke(item)
                dialog.dismiss()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = libraryAdapter
        libraryAdapter.itemTouchHelper.attachToRecyclerView(recyclerView)

        actionAddButton.setOnClickListener {
            showCreateDialog(context, libraryAdapter)
        }

        actionEditButton.setOnClickListener {
            isEditing = !isEditing
            actionAddButton.isVisible = isEditing
            libraryAdapter.changeEditingStatus(isEditing)
            actionEditButton.setImageResource(
                if (isEditing) R.drawable.ic_sharp_clear_24
                else R.drawable.ic_baseline_edit_24
            )
        }

        libraryAdapter.submitList(list)
        dialog.show()
    }

    /**It will display a dialog to enter the name of a new library to add. Max characters = 30, see dialog_add_folder.xml**/
    private fun showCreateDialog(context: Context, libraryAdapter: LibrarySectionAdapter) {
        val inputView = LayoutInflater.from(context).inflate(R.layout.dialog_add_folder, null)
        val editText = inputView.findViewById<EditText>(R.id.editFolderName)

        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(R.string.library_create)
            .setView(inputView)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val title = editText.text.toString().trim()
                val safeKey = title.toLibraryKey()
                if (title.isNotEmpty() && safeKey.isNotEmpty()) {
                    try {
                        val current = context.getLibraries()
                        //Assign an ID; they are always sequential
                        //Note: IDs are not used when merging a backup; titles are used instead
                        val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
                        val nextPosition = (current.maxOfOrNull { it.position } ?: 0) + 1
                        context.addLibrary(
                            DefaultLibrary(
                                id = nextId,
                                key = safeKey,
                                title = title,
                                editable = true,
                                position = nextPosition
                            )
                        )
                        refreshList(context, libraryAdapter)
                    }catch (e: Exception){
                        showToast(e.message)
                    }
                }
                else{
                    showToast(R.string.library_error_invalid_name)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**This will display a dialog with the name of the selected library and allow it to be renamed.
    The maximum number of characters is always 30, see dialog_add_folder**/
    fun showRenameDialog(context: Context, item: DefaultLibrary, onComplete: () -> Unit) {
        val inputView = LayoutInflater.from(context).inflate(R.layout.dialog_add_folder, null)
        val editText = inputView.findViewById<EditText>(R.id.editFolderName)
        editText.setText(item.title)

        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(R.string.library_rename)
            .setView(inputView)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val title = editText.text.toString().trim()
                val safeKey = title.toLibraryKey()
                if (title.isNotEmpty() && safeKey.isNotEmpty()) {
                    context.updateLibrary(item.copy(title = title, key = safeKey))
                    onComplete()
                }
                else {
                    showToast(R.string.library_error_invalid_name)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Displays a bottom dialog for selecting a library to which you want to transfer all the books from the selected library.
     The library is deleted after it has been emptied **/
    fun showMergeDialog(context: Context, item: DefaultLibrary, adapter: LibrarySectionAdapter) {
        //Get all libraries except the selected one
        val targetCandidates = context.getLibraries().filter { it.id != item.id }
        if (targetCandidates.isEmpty()) return

        context.showBottomDialog(
            items = targetCandidates.map { it.title },
            selectedIndex = -1,
            name = context.getString(R.string.library_merge),
            showApply = false,
            dismissCallback = {},
            callback = { which ->
                val target = targetCandidates.getOrNull(which) ?: return@showBottomDialog
                context.mergeLibraries(item.id, target.id)
                refreshList(context, adapter)
            }
        )
    }

    /**Delete a library dialog**/
    fun showDeleteDialog(context: Context, item: DefaultLibrary, adapter: LibrarySectionAdapter) {
        //the library should be empty before deleting it
        val inUse = context.getLibraryBookmarkCount(item.id)
        if (inUse > 0) {
            showToast(R.string.library_delete_empty_only_message)
            return
        }

        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(R.string.library_delete)
            .setMessage(context.getString(R.string.permanently_delete_format).format(item.title))
            .setPositiveButton(R.string.delete) { dialog, _ ->
                context.deleteLibrary(item.id)
                refreshList(context, adapter)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}