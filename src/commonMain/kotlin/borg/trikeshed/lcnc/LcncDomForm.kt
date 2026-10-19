package borg.trikeshed.lcnc

/** Scalar parameter widgets, authored as data. Dynamic options must be projected upstream. */
object LcncDomForm {
    fun fields(value: Any?): Map<String, LcncPortContract.LcncParamSpec> {
        val raw = requireNotNull(value as? Map<*, *>) { "dom_form_fields_required" }
        require(raw.size in 1..64) { "dom_form_field_count" }
        return raw.entries.associate { (key, value) ->
            val name = requireNotNull(key as? String).also { require(it.isNotBlank()) }
            val spec = requireNotNull(value as? Map<*, *>) { "dom_form_field_spec" }
            require(spec.keys.all { it in setOf("v", "opts", "ta", "ph", "optional") }) { "dom_form_scalar_spec_only" }
            fun text(key: String): String = if (key !in spec) "" else
                requireNotNull(spec[key] as? String) { "dom_form_spec_$key" }
            fun flag(key: String): Boolean = if (key !in spec) false else
                requireNotNull(spec[key] as? Boolean) { "dom_form_spec_$key" }
            val opts = if ("opts" !in spec) emptyList() else
                requireNotNull(spec["opts"] as? List<*>) { "dom_form_spec_opts" }.map {
                    requireNotNull(it as? String) { "dom_form_spec_option" }
                }
            val default = text("v")
            require(opts.isEmpty() || default.isEmpty() || default in opts) { "dom_form_default_option" }
            name to LcncPortContract.LcncParamSpec(default, opts, flag("ta"), text("ph"), optional = flag("optional"))
        }
    }

    /** A gesture is bound to a completed board projection, not to arbitrary page-supplied schema. */
    fun event(value: Any?, source: String, targets: Map<String, LcncNode>, retained: LcncRetainedFrame) {
        val event = requireNotNull(value as? Map<*, *>) { "invalid_dom_event" }
        require((event["id"] as? String)?.isNotBlank() == true) { "dom_event_id_required" }
        val target = requireNotNull(event["target"] as? String) { "dom_event_target_required" }
        require(targets[target]?.type == "dom.board") { "dom_event_target_required" }
        val output = retained.outputs[target].orEmpty()
        require(output["events"] == source) { "dom_event_source_mismatch" }
        val kind = event["kind"]
        require(kind in setOf("create", "edit", "move")) { "dom_event_kind" }
        if (kind != "create") require(event["item"] is Map<*, *>) { "dom_event_item_required" }
        if (kind == "move") {
            require(event["itemId"] != null && event["from"] is String && event["to"] is String) { "invalid_dom_move" }
            return
        }
        val specs = fields(output["fields"])
        val values = requireNotNull(event["fields"] as? Map<*, *>) { "dom_event_fields_required" }
        require(values.isNotEmpty() && values.keys.all { it in specs }) { "dom_event_fields_mismatch" }
        for ((key, raw) in values) {
            val spec = specs.getValue(key as String)
            val text = requireNotNull(raw as? String) { "dom_event_scalar_value" }
            require(spec.optional || text.isNotBlank()) { "dom_event_required_field" }
            require(spec.opts.isEmpty() || text in spec.opts) { "dom_event_option" }
        }
        if (kind == "create") require(specs.all { (name, spec) -> spec.optional || name in values }) { "dom_event_required_field" }
    }
}
