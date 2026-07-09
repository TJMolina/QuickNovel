package com.lagradost.quicknovel.util

object CommonHeaders  {
    val useCloudflareKillerHeader = "useCloudflareKiller" to "true"
    val useIgnore500Header = "useIgnore500" to "true"

    fun ajaxHeaders(refer:String = "") =
        if(refer.isNotEmpty())
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refer,
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
        else emptyMap()
}