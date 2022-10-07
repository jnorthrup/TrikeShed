package borg.trikeshed.placeholder.nars.parser

/** for narsese we support the following regex character nodes from the bnf grammar above:
 */
enum class RecognizerClassBlocks(
    node: RecognizerNode
) {
    //    |   Digits	|	[:digit:]	|		|	\p{Digit} or \d	|	[0-9]
    Digits(RecognizerNode(RecognizerTier.byDistinct(*"0123456789".encodeToByteArray()), true, terminal = true)),

    //|   ASCII characters	|		|	[:ascii:][38]	|	\p{ASCII}	|	[\x00-\x7F]
    AsciiCharacters(
        RecognizerNode(RecognizerTier.byDistinct(*(0..0x7f).map { it.toByte() }.toByteArray()), true, terminal = true)
    ),

    //|   Alphanumeric characters	|	[:alnum:]	|		|	\p{Alnum}	|	[A-Za-z0-9]
    AlphanumericCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Alphanumeric characters plus "_"	|		|	[:word:][38]	|	\w	|	[A-Za-z0-9_]
    AlphanumericCharactersPlusUnderscore(
        RecognizerNode(
            RecognizerTier.byDistinct(*"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_".encodeToByteArray()),
            true,
            terminal = true
        )
    ),


    //|   Alphabetic characters	|	[:alpha:]	|		|	\p{Alpha}	|	[A-Za-z]
    AlphabeticCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Space and tab	|	[:blank:]	|		|	\p{Blank}	|	[ \t]
    SpaceAndTab(
        RecognizerNode(RecognizerTier.byDistinct(*" \t".encodeToByteArray()), true, terminal = true)
    ),

    //|   Word boundaries	|		|		|	\b	|	(?<=\W)(?=\w)|(?<=\w)(?=\W)
    WordBoundaries(
        RecognizerNode(RecognizerTier.byDistinct(*" \t".encodeToByteArray()), true, terminal = true, negate = true)
    ),

    //|   Non-word boundaries	|		|		|	\B	|	(?<=\W)(?=\W)|(?<=\w)(?=\w)
    NonWordBoundaries(
        RecognizerNode(RecognizerTier.byDistinct(*" \t".encodeToByteArray()), true, terminal = true)
    ),

    //|   Control characters	|	[:cntrl:]	|		|	\p{Cntrl}	|	[\x00-\x1F\x7F]
    ControlCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u000B\u000C\u000E\u000F\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F\u007F".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Non-digits	|		|		|	\D	|	[^0-9]
    NonDigits(
        RecognizerNode(RecognizerTier.byDistinct(*"0123456789".encodeToByteArray()), true, terminal = true)
    ),

    //|   Visible characters	|	[:graph:]	|		|	\p{Graph}	|	[\x21-\x7E] ,
    VisibleCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"\u0021\u0022\u0023\u0024\u0025\u0026\u0027\u0028\u0029\u002A\u002B\u002C\u002D\u002E\u002F\u003A\u003B\u003C\u003D\u003E\u003F\u0040\u005B\u005C\u005D\u005E\u005F\u0060\u007B\u007C\u007D\u007E".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Lowercase letters	|	[:lower:]	|		|	\p{Lower}	|	[a-z]
    LowercaseLetters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"abcdefghijklmnopqrstuvwxyz".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Non-printable characters	|	[:cntrl:]	|		|	\p{Cntrl}	|	[\x00-\x1F\x7F]
    NonPrintableCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u000B\u000C\u000E\u000F\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F\u007F".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Printable characters	|	[:print:]	|		|	\p{Print}	|	[\x20-\x7E]
    PrintableCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"\u0020\u0021\u0022\u0023\u0024\u0025\u0026\u0027\u0028\u0029\u002A\u002B\u002C\u002D\u002E\u002F\u003A\u003B\u003C\u003D\u003E\u003F\u0040\u005B\u005C\u005D\u005E\u005F\u0060\u007B\u007C\u007D\u007E".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Punctuation characters	|	[:punct:]	|		|	\p{Punct}	|	[\x21-\x2F\x3A-\x40\x5B-\x60\x7B-\x7E]
    PunctuationCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"\u0021\u0022\u0023\u0024\u0025\u0026\u0027\u0028\u0029\u002A\u002B\u002C\u002D\u002E\u002F\u003A\u003B\u003C\u003D\u003E\u003F\u0040\u005B\u005C\u005D\u005E\u005F\u0060\u007B\u007C\u007D\u007E".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Space characters	|	[:space:]	|		|	\p{Space}	|	[ \t\r
    SpaceCharacters(
        RecognizerNode(RecognizerTier.byDistinct(*" \t\r\n".encodeToByteArray()), true, terminal = true)
    ),

    //|   Uppercase letters	|	[:upper:]	|		|	\p{Upper}	|	[A-Z]
    UppercaseLetters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"ABCDEFGHIJKLMNOPQRSTUVWXYZ".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Whitespace characters	|	[:space:]	|		|	\p{Space}	|	[ \t\r\n]
    WhitespaceCharacters(
        RecognizerNode(RecognizerTier.byDistinct(*" \t\r\n".encodeToByteArray()), true, terminal = true)
    ),

    //|   Word characters	|	[:alnum:]	|		|	\w	|	[a-zA-Z0-9_]
    WordCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".encodeToByteArray()),
            true,
            terminal = true
        )
    ),

    //|   Non-word characters	|		|		|	\W	|	[^a-zA-Z0-9_]
    NonWordCharacters(
        RecognizerNode(
            RecognizerTier.byDistinct(*"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".encodeToByteArray()),
            true,
            terminal = true,
            negate = true
        )
    ),
}