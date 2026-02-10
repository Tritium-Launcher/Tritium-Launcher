package io.github.footermandev.tritium.ui.theme.qt

import io.qt.gui.QIcon
import io.qt.gui.QPixmap

val QPixmap.icon: QIcon get() = QIcon(this)