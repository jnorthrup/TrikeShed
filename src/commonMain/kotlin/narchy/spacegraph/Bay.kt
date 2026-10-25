package narchy.spacegraph

import kotlin.math.max

/**
 * A shelving bay (one per project, one for the concept pool): its books in rows, each row on a board, between a left and a
 * right post and in front of a back panel, with a ladder at its left end. [stand] places the books and fixes the extent
 * (x0..x1, 0..h, z..[front]); [solids] is the wood.
 *
 * The placement follows rules that keep the wood from hiding what stands on it:
 *  - Every book stands in the bay's front plane ([front]), flush with the front of its posts and boards instead of set back
 *    into the shelf, and the ladder is flush with it too. Nothing of the bay is nearer than a book, so for any eye in front
 *    of that plane no wood lies between the eye and a book.
 *  - In that plane a book clears both posts by [gap], rests on its board, and has the next board [gap] above the row's
 *    tallest book, so a book and the wood never share a point of it.
 *
 * [CuratorScope.frame] paints the wood of every bay before anything else. Within a bay, with the rules above, that is what a
 * depth buffer would give, and it takes away what one depth key per face cannot order: a tall wall keyed by its middle, seen
 * by an eye that looks down on it, was painted over the lower rows of books standing in front of it. Across bays it is a
 * choice and not a depth buffer's answer: the wood of a nearer bay does not hide the books of a farther one either.
 */
internal class Bay(val name: String, val discs: IntArray) {
    var x0 = 0.0; var x1 = 0.0; var h = 0.0; var z = 0.0; var d = 0.0; var post = 0.0; var board = 0.0
    /** The clear space between neighbouring books, between a row and a post, and above a row's tallest book. */
    var gap = 0.0
    /** The top of each board, lowest first: one under every row and one over the last. */
    val boards = ArrayList<Double>()

    /** The width of the ladder at the bay's left end. */
    val ladder: Double get() = post * 4
    /** The front of the bay's posts and boards. */
    val front: Double get() = z + d

    /**
     * Stands the books ([discs] in shelf order, [radius] of the i-th) in rows of [CuratorScope.ROW], the first at [left] and the
     * bay's back at [back], and returns each book's center.
     */
    fun stand(radius: (Int) -> Double, left: Double, back: Double): Array<Vec3> {
        val big = discs.indices.maxOf(radius)
        post = big * .05 + 2; board = big * .03 + 1.5; d = big * .9
        gap = big * .15 + CuratorScope.PAD * 4
        x0 = left; z = back
        val at = arrayOfNulls<Vec3>(discs.size)
        var y = board; var width = 0.0
        boards.clear(); boards.add(board)
        for (row in discs.indices.chunked(CuratorScope.ROW)) {
            val rowH = row.maxOf(radius) * 2 + gap
            var x = x0 + ladder + post + gap
            for (b in row) { at[b] = Vec3(x + radius(b), y + radius(b), front); x += radius(b) * 2 + gap }
            width = max(width, x - x0)
            y += rowH + board; boards.add(y)
        }
        x1 = x0 + width + post; h = y
        return Array(discs.size) { at[it]!! }
    }

    /** The wood as boxes, in a fixed order: the back panel, the left and right posts, the boards, the ladder's two rails, its rungs. */
    fun solids(): List<Bounds3> {
        val out = ArrayList<Bounds3>()
        out.add(Bounds3(Vec3(x0, 0.0, z), Vec3(x1, h, z + post * .4)))
        out.add(Bounds3(Vec3(x0 + ladder, 0.0, z), Vec3(x0 + ladder + post, h, z + d)))
        out.add(Bounds3(Vec3(x1 - post, 0.0, z), Vec3(x1, h, z + d)))
        for (y in boards) out.add(Bounds3(Vec3(x0 + ladder, y - board, z), Vec3(x1, y, z + d)))
        val rail = post * .5; val back = front - rail
        out.add(Bounds3(Vec3(x0, 0.0, back), Vec3(x0 + rail, h + ladder, front)))
        out.add(Bounds3(Vec3(x0 + ladder - rail, 0.0, back), Vec3(x0 + ladder, h + ladder, front)))
        val rungs = max(2, (h / (ladder * .9)).toInt())
        for (r in 1..rungs) {
            val y = r * (h + ladder) / (rungs + 1)
            out.add(Bounds3(Vec3(x0 + rail, y, back), Vec3(x0 + ladder - rail, y + rail * .6, front)))
        }
        return out
    }
}
