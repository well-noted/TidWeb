package com.tiddlywikibrowser.model

data class TiddlerTemplate(
    val name: String,
    val fileName: String,
    val description: String? = null,
    val content: String? = null
)