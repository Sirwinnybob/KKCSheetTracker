package com.kkc.updateragent.update

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object Json {
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
}
