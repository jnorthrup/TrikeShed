package one.xio

/**
 * non-public config class for FSM specific configs
 */
internal object Config {
    fun get(ox_var: String, vararg defaultVal: String?): String? {
        val javapropname: String = ("1xio."
                + ox_var.toLowerCase().replaceAll("^1xio_", "").replace(
            '_',
            '.'
        ))
        val oxenv: String? = System.getenv(ox_var)
        var `var`: String? = if (null == oxenv) System.getProperty(javapropname) else oxenv
        `var` = if (null == `var` && defaultVal.size > 0) defaultVal.get(0) else `var`
        if (null != `var`) {
            System.setProperty(javapropname, `var`)
            System.err
                .println("// -D" + javapropname + "=" + "\"" + `var` + "\"")
        }
        return `var`
    }
}
