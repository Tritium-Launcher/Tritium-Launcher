package io.github.footermandev.tritium

var debugLogging: Boolean = false

internal fun manageArguments(args: List<String>) {

    debugLogging = args.any { it == "-debug" || it == "--debug" || it == "-d"  }

}