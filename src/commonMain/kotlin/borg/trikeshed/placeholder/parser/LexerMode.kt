package borg.trikeshed.placeholder.parser

/**
 * this defines the lexer mode, which is a state machine that defines the next lexer mode and the next lexer mode's behavior
 *
 * define infix:
 */
enum class LexerMode {
    /** noskip
     *  this is the default mode, it will not skip whitespace and will not skip comments
     */
    NOSKIP,

    /** skipws
     *  this mode will skip whitespace and will skip comments
     */
    SKIPWS,

    /** skipc
     *  this mode will not skip whitespace and will skip comments
     */
    SKIPC,

    /** skipall
     *  this mode will skip whitespace and will skip comments
     */
    SKIPALL,

    /** noescape
     * this mode will not enter escape for any reason
     */
    NOESCAPE,
}
