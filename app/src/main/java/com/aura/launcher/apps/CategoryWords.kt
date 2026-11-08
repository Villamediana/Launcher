package com.aura.launcher.apps

/**
 * Words derived from Android's own `ApplicationInfo.category`.
 *
 * Unlike a per-app synonym list it does not grow — Android supplies the
 * category for every app ever installed, and this only translates the nine
 * constants into words a person would type.
 */
object CategoryWords {

    fun of(category: Int): List<String> = when (category) {
        0 -> listOf("game", "games", "jogo", "jogos", "jugar", "play")
        1 -> listOf("music", "audio", "song", "musica", "som", "podcast", "radio")
        2 -> listOf("video", "movie", "film", "tv", "filme", "serie", "watch", "assistir")
        3 -> listOf("photo", "photos", "image", "gallery", "foto", "fotos", "imagem", "galeria", "camera")
        4 -> listOf("social", "chat", "message", "messages", "friends", "mensagem", "mensagens", "conversa", "rede social")
        5 -> listOf("news", "magazine", "noticia", "noticias", "jornal", "read")
        6 -> listOf("maps", "navigation", "directions", "mapa", "mapas", "rota", "navegar", "gps")
        7 -> listOf("productivity", "work", "notes", "document", "trabalho", "nota", "notas", "documento")
        8 -> listOf("accessibility", "acessibilidade")
        else -> emptyList()
    }
}
