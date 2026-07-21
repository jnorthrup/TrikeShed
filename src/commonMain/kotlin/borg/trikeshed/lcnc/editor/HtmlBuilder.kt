/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

class HtmlBuilder {
    private val sb = StringBuilder()
    
    fun div(classes: String = "", id: String = "", block: HtmlBuilder.() -> Unit = {}) {
        tag("div", classes, id, block)
    }
    
    fun input(type: String, value: String, classes: String = "", id: String = "", onChange: String = "") {
        sb.append("<input type=\"$type\" value=\"$value\"")
        if (classes.isNotEmpty()) sb.append(" class=\"$classes\"")
        if (id.isNotEmpty()) sb.append(" id=\"$id\"")
        if (onChange.isNotEmpty()) sb.append(" onchange=\"$onChange\"")
        sb.append("/>")
    }
    
    fun text(text: String) {
        sb.append(text)
    }
    
    private fun tag(name: String, classes: String, id: String, block: HtmlBuilder.() -> Unit) {
        sb.append("<$name")
        if (classes.isNotEmpty()) sb.append(" class=\"$classes\"")
        if (id.isNotEmpty()) sb.append(" id=\"$id\"")
        sb.append(">")
        block()
        sb.append("</$name>")
    }
    
    override fun toString(): String = sb.toString()
}

fun html(block: HtmlBuilder.() -> Unit): String {
    val builder = HtmlBuilder()
    builder.block()
    return builder.toString()
}
