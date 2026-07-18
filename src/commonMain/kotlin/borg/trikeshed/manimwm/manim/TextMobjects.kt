package borg.trikeshed.manimwm.manim

open class Tex(val texString: String) : VMobject()
open class Text(val text: String, val font: String = "sans-serif", val fontSize: Double = 48.0) : VMobject()
open class MathTex(val texStrings: List<String>) : VMobject()
open class MarkupText(val text: String, val font: String = "sans-serif", val fontSize: Double = 48.0) : VMobject()
