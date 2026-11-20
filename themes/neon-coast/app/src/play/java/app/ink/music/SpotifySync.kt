package app.ink.music

import android.content.Context

/** The play build signs in to no Spotify: nothing kept in step. */
object SpotifySync {
    fun count(c: Context): Int = 0
    fun keep(c: Context, spotify: String, name: String, tube: TubeList, songs: List<Import.Said>) {}
    suspend fun run(c: Context, force: Boolean = false) {}
}
