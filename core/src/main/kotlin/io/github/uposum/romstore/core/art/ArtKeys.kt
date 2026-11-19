package io.github.uposum.romstore.core.art

/**
 * The keys baked into the app so covers work out of the box, the same ones the
 * Decky plugin ships in backend/settings.py. Never log them; rotate them if
 * they leak, rather than removing the feature.
 */
object ArtKeys {
    /** The maintainer's free SteamGridDB key (DEFAULT_STEAMGRIDDB_KEY). */
    const val STEAMGRIDDB_KEY = "226014b82073506b73fd9a462806a065"

    /** IGDB (Twitch) client credentials (igdb_client_id / igdb_client_secret). */
    const val IGDB_CLIENT_ID = "g5l93pm0saj4mnqpya3xujmmmcsna1"
    const val IGDB_CLIENT_SECRET = "v2qtz1potmdvx2blhcvg75ccc3ver7"

    /** The user's own SteamGridDB key when set, else the built-in one. */
    fun steamGridDbKey(userKey: String?): String = userKey?.trim()?.takeIf { it.isNotEmpty() } ?: STEAMGRIDDB_KEY
}
