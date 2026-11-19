package io.github.uposum.romstore.core.model

import kotlinx.serialization.Serializable

/** One file (or folder) of an installed game. */
@Serializable
data class LibraryFile(val name: String, val size: Long, val folder: Boolean = false, val uri: String = "")

/**
 * A game in the user's ROM library: the files of one console folder that
 * share a title ("Game (Disc 1).chd", "Game.m3u"), listed and removed together.
 */
@Serializable
data class LibraryGame(
    val id: String,
    val system: String,
    /** File name without extension (the first file's). */
    val title: String,
    /** Where the console folder is (a content:// tree document URI on Android). */
    val folder: String,
    val files: List<LibraryFile>,
    val size: Long,
    val modified: Long,
    var cover: String? = null,
    /** The game's real name, from the title lists or the box-art index. */
    var name: String? = null,
    var needsUnpack: Boolean = false,
)
