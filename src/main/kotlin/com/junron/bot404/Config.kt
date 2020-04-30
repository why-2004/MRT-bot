package com.junron.bot404

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File


@Serializable
data class Config(
    val discordToken: String,
    val botPrefix: String,
    val hwbotPrefix: String,
    val hwboardName: String = "404",
    val projectId: String
)

val config =
    Json.parse(Config.serializer(), File("config.json").readText())
