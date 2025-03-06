package com.tiddlywikibrowser

/**
 * Represents a TiddlyWiki template that can be used to create a single file tiddler
 *
 * @property name The display name of the template
 * @property fileName The file name of the template in the assets folder
 * @property description Optional description of the template
 */
data class TiddlerTemplate(
    val name: String,
    val fileName: String,
    val description: String? = null
)