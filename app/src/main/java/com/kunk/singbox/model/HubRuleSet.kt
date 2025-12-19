package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

data class HubRuleSet(
    @SerializedName("name") val name: String,
    @SerializedName("ruleCount") val ruleCount: Int,
    @SerializedName("tags") val tags: List<String>,
    @SerializedName("description") val description: String = "",
    @SerializedName("sourceUrl") val sourceUrl: String = "",
    @SerializedName("binaryUrl") val binaryUrl: String = ""
)
