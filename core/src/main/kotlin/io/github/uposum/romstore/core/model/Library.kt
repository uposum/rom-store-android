package io.github.uposum.romstore.core.model

import kotlinx.serialization.Serializable

/** An update or DLC file that belongs to an installed game. */
@Serializable
data class LibraryAddon(
    val name: String,
    val size: Long,
    val kind: ContentKind,
    val version: String? = null,
    val uri: String = "",
    /** The subfolder of the console folder it is in ("updates", "dlc"), or null. */
    val subfolder: String? = null,
)

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
    /** Updates and DLC found for this game (in updates/, dlc/ or next to it). */
    val addons: List<LibraryAddon> = emptyList(),
)
