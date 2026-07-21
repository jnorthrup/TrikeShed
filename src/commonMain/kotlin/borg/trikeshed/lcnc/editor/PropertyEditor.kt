/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.collections.associative.PropertySchema
import borg.trikeshed.lcnc.collections.associative.PropertyType

data class PropertyChangeEvent(
    val oldValue: Any?,
    val newValue: Any?,
    val propertySchema: PropertySchema
)

interface PropertyEditor {
    val schema: PropertySchema
    val value: Any?
    val onChange: ((PropertyChangeEvent) -> Unit)?
    
    fun renderHtml(): String
    fun validate(input: Any?): Boolean
    
    fun handleInput(input: Any?) {
        if (validate(input)) {
            onChange?.invoke(PropertyChangeEvent(value, input, schema))
        }
    }
}

class TextPropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        input(
            type = "text",
            value = value?.toString() ?: "",
            classes = "lcnc-prop-text",
            id = "prop-${schema.id}",
            onChange = "window.lcncPropChange('${schema.id}', this.value)"
        )
    }

    override fun validate(input: Any?): Boolean = true
}

class SelectPropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        val options = (schema.configuration?.get("options") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val builder = HtmlBuilder()
        builder.div(classes = "lcnc-prop-select", id = "prop-${schema.id}") {
            text("<select onchange=\"window.lcncPropChange('${schema.id}', this.value)\">")
            for (opt in options) {
                val selected = if (opt == value?.toString()) " selected" else ""
                text("<option value=\"$opt\"$selected>$opt</option>")
            }
            text("</select>")
        }
        text(builder.toString())
    }

    override fun validate(input: Any?): Boolean {
        val options = (schema.configuration?.get("options") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return options.contains(input?.toString())
    }
}

class CheckboxPropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        val isChecked = value as? Boolean ?: false
        val checkedAttr = if (isChecked) " checked" else ""
        text("<input type=\"checkbox\" class=\"lcnc-prop-checkbox\" id=\"prop-${schema.id}\" onchange=\"window.lcncPropChange('${schema.id}', this.checked)\"$checkedAttr/>")
    }

    override fun validate(input: Any?): Boolean = input is Boolean
}

class NumberPropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        input(
            type = "number",
            value = value?.toString() ?: "",
            classes = "lcnc-prop-number",
            id = "prop-${schema.id}",
            onChange = "window.lcncPropChange('${schema.id}', this.value)"
        )
    }

    override fun validate(input: Any?): Boolean {
        if (input == null) return true
        val str = input.toString()
        return str.toDoubleOrNull() != null
    }
}

class DatePropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        input(
            type = "date",
            value = value?.toString() ?: "",
            classes = "lcnc-prop-date",
            id = "prop-${schema.id}",
            onChange = "window.lcncPropChange('${schema.id}', this.value)"
        )
    }

    override fun validate(input: Any?): Boolean = true
}

class UrlPropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        input(
            type = "url",
            value = value?.toString() ?: "",
            classes = "lcnc-prop-url",
            id = "prop-${schema.id}",
            onChange = "window.lcncPropChange('${schema.id}', this.value)"
        )
    }

    override fun validate(input: Any?): Boolean {
        if (input == null) return true
        val str = input.toString()
        return str.startsWith("http://") || str.startsWith("https://")
    }
}

class EmailPropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        input(
            type = "email",
            value = value?.toString() ?: "",
            classes = "lcnc-prop-email",
            id = "prop-${schema.id}",
            onChange = "window.lcncPropChange('${schema.id}', this.value)"
        )
    }

    override fun validate(input: Any?): Boolean {
        if (input == null) return true
        val str = input.toString()
        return str.contains("@") && str.contains(".")
    }
}

class PhonePropertyEditor(
    override val schema: PropertySchema,
    override val value: Any?,
    override val onChange: ((PropertyChangeEvent) -> Unit)? = null
) : PropertyEditor {
    override fun renderHtml(): String = html {
        input(
            type = "tel",
            value = value?.toString() ?: "",
            classes = "lcnc-prop-phone",
            id = "prop-${schema.id}",
            onChange = "window.lcncPropChange('${schema.id}', this.value)"
        )
    }

    override fun validate(input: Any?): Boolean {
        if (input == null) return true
        val str = input.toString().filter { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
        return str.length >= 7
    }
}
