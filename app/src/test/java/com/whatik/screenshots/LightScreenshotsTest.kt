package com.whatik.screenshots

import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tutte le schermate in tema chiaro (file *-light.png). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = Phone.LIGHT)
class LightScreenshotsTest : ScreenScenarios("light")
