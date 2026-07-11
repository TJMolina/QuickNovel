package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.newChapterData

class NovelPhoenixProvider: NovelFireProvider() {
    override val name = "Novel Phoenix"
    override val mainUrl = "https://novelphoenix.com"
    override val iconId = R.drawable.icon_novelphoenix
}