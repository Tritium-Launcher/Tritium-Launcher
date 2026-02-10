package io.github.footermandev.tritium.coroutines

import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

object UIDispatcher : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        runOnGuiThread { block.run() }
    }


}