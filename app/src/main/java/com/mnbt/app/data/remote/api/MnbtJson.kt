package com.mnbt.app.data.remote.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** 全项目共用一个 Json 实例：它内部带缓存，反复 new 会明显拖慢解析。 */
@OptIn(ExperimentalSerializationApi::class)
val MnbtJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}
