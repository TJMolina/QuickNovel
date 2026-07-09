package com.lagradost.quicknovel.ui.updates.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.BottomSheetAddWatchBinding
import com.lagradost.quicknovel.databinding.ItemWatchNovelSelectBinding
import com.lagradost.quicknovel.util.ResultCached

/**
 * This class is to massive select novels to watch. This is a fragment*/
class AddWatchBottomSheet(
    private val novels: List<ResultCached>,
    private val initialIds: Set<Int> = emptySet(),
    private val onAccept: (List<ResultCached>) -> Unit,
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddWatchBinding? = null
    private val binding get() = _binding!!
    private var selected = mutableSetOf<Int>()
    private var filteredNovels: List<ResultCached> = novels
    private var currentQuery = ""
    private var currentFilterMode = 0 // 0: All, 1: Watching, 2: No watching

    private fun applyFilters(adapter: NovelSelectAdapter) {
        filteredNovels = novels.filter { novel ->
            val matchesQuery = novel.name.contains(currentQuery, ignoreCase = true)

            val matchesFilter = when (currentFilterMode) {
                1 -> initialIds.contains(novel.id)
                2 -> !initialIds.contains(novel.id)
                else -> true
            }

            matchesQuery && matchesFilter
        }
        adapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED

            val displayMetrics = resources.displayMetrics
            it.layoutParams.height = (displayMetrics.heightPixels * 0.9).toInt()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList("selected_ids", ArrayList(selected.toList()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            savedInstanceState.getIntegerArrayList("selected_ids")?.let {
                selected = it.toMutableSet()
            }
        } else {
            selected = initialIds.toMutableSet()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAddWatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = NovelSelectAdapter()
        binding.recyclerNovels.adapter = adapter

        val filterOptions = resources.getStringArray(R.array.updates_filter_options)
        val spinnerAdapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                filterOptions
            )
        binding.filterSpinner.adapter = spinnerAdapter

        binding.searchBar.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                currentQuery = query
                applyFilters(adapter)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                currentQuery = newText
                applyFilters(adapter)
                return true
            }
        })

        binding.filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                currentFilterMode = position
                applyFilters(adapter)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnAccept.setOnClickListener {
            val result = novels.filter { it.id in selected }
            if (result.isNotEmpty()) {
                onAccept(result)
            }
            dismiss()
        }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }

    private inner class NovelSelectAdapter : RecyclerView.Adapter<NovelSelectAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemWatchNovelSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = filteredNovels.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(filteredNovels[position])

        inner class VH(private val b: ItemWatchNovelSelectBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(novel: ResultCached) {
                b.novelName.text = novel.name
                b.checkIcon.isVisible = selected.contains(novel.id)
                b.root.setOnClickListener {
                    val isSelected = selected.contains(novel.id)
                    if (isSelected)
                        selected.remove(novel.id)
                    else selected.add(novel.id)
                    b.checkIcon.isVisible = !isSelected
                }
            }
        }
    }
}