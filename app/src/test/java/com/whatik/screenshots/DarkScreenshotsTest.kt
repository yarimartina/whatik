package com.whatik.screenshots

import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tutte le schermate in tema scuro (file *-dark.png). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = Phone.DARK)
class DarkScreenshotsTest : ScreenScenarios("dark")
