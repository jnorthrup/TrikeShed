package borg.trikeshed.parse

import borg.trikeshed.common.*
import borg.trikeshed.cursor.*
import borg.trikeshed.io.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.Join.Companion.emptySeriesOf

/** forward scanner of commas, quotes, and newlines
 */
object CSVUtil {

    /**
     * Parses a CSV string into fields
     * @param text The CSV text to parse
     * @param collectEvidence Whether to collect type evidence for columns
     * @return List of DelimitRange representing the parsed fields
     */
    fun parseLongSeries(csvFile: LongSeries<Byte>, collectEvidence: Boolean = true): Cursor {


        val lines: CowSeriesHandle<Join<Long, Join<Int, (Int) -> Join<UShort, UShort>>>> =
            emptySeriesOf<Join<Long, Series<DelimitRange>>>().cow

        val evidence:Twin<TypeEvidence> =TypeEvidence() j TypeEvidence()
        var c = 0L
        while (csvFile.a > c) {
