package io.github.footermandev.tritium.koin

import org.koin.core.KoinApplication

object Koin {
    lateinit var app: KoinApplication
    val koin get() = app.koin
}