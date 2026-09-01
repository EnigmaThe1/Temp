package com.llmcouncil.mobile.data

import okhttp3.MediaType
import okhttp3.RequestBody

@Suppress("DEPRECATION")
fun String.toRequestBody(mediaType: MediaType): RequestBody = RequestBody.create(mediaType, this)
