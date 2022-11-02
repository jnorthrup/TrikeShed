package borg.trikeshed.lib.collections
//pet project

///**
//A "Plex" is a kind of array with the following properties:
//
//Plexes may have arbitrary upper and lower index bounds. For example a Plex may be declared to run from
//indices -10 .. 10.
//
//Plexes may be dynamically expanded at both the lower and upper bounds of the array in steps of one element.
//Only elements that have been specifically initialized or added may be accessed.
//Elements may be accessed via indices. Indices are always checked for validity at run time. Plexes may be traversed via
//simple variations of standard array indexing loops.
//Plex elements may be accessed and traversed via Pixes.
//Plex-to-Plex assignment and related operations on entire Plexes are supported.
//Plex classes contain methods to help programmers check the validity of indexing and pointer operations.
//Plexes form "natural" base classes for many restricted-access data structures relying on logically contiguous indices,
//such as array-based stacks and queues.
//
//Four subclasses of Plexes are supported: A FPlex is a Plex that may only grow or shrink within declared bounds; an
//XPlex may dynamically grow or shrink without bounds; an RPlex is the same as an XPlex but better supports indexing with
//poor locality of reference; a MPlex may grow or shrink, and additionally allows the logical deletion and restoration of
//elements. Because these classes are virtual subclasses of the "abstract" class Plex, it is possible to write user code
//such as void f(Plex& a) ... that operates on any kind of Plex. However, as with nearly any virtual class, specifying
//the particular Plex class being used results in more efficient code.
//
//Plexes are implemented as a linked list of IChunks. Each chunk contains a part of the array. Chunk sizes may be
//specified within Plex constructors. Default versions also exist, that use a #define'd default. Plexes grow by filling
//unused space in existing chunks, if possible, else, except for FPlexes, by adding another chunk. Whenever Plexes grow
//by a new chunk, the default element constructors (i.e., those which take no arguments) for all chunk elements are
//called at once. When Plexes shrink, destructors for the elements are not called until an entire chunk is freed. For
//this reason, Plexes (like C++ arrays) should only be used for elements with default constructors and destructors
//that have no side effects.
//
//Plexes may be indexed and used like arrays, although traversal syntax is slightly different. Even though Plexes
//maintain elements in lists of chunks, they are implemented so that iteration and other constructs that maintain
//locality of reference require very little overhead over that for simple array traversal Pix-based traversal is also
//supported. For example, for a c++ plex, p, of ints, the following traversal methods could be used.
//
//```c++
//for (int i = p.low(); i < p.fence(); p.next(i)) use(p[i]);
//for (int i = p.high(); i > p.ecnef(); p.prev(i)) use(p[i]);
//for (Pix t = p.first(); t != 0; p.next(t)) use(p(i));
//for (Pix t = p.last(); t != 0; p.prev(t)) use(p(i));
//```
//
// the kotlin translation of the above code is:
//```kotlin
//for (i in p.low() until p.fence()) use(p[i])
//for (i in p.high() downTo p.ecnef()) use(p[i])
//for (t in p.first()..p.last()) use(p[i])
//for (t in p.last() downTo p.first()) use(p[i])
//```
//XPlexes and MPlexes are less than optimal for applications in which widely scattered elements are indexed, as might occur when using Plexes as hash tables or "manually" allocated linked lists. In such applications, RPlexes are often preferable. RPlexes use a secondary chunk index table that requires slightly greater, but entirely uniform overhead per index operation.
//
//Even though they may grow in either direction, Plexes are normally constructed so that their "natural" growth direction is upwards, in that default chunk construction leaves free space, if present, at the end of the plex. However, if the chunksize arguments to constructors are negative, they leave space at the beginning.
//
//All versions of Plexes support the following basic capabilities.  Assume declarations of Plex p, q, int i, j, base element x, and Pix pix.
//
// */
//interface Pix<T> {
//    val plex: Plex<T>
//    val index: Int
//}
//
//interface Plex<T> {
//    //    Plex p; /// Declares p to be an initially zero-sized Plex with low index of zero, and the default chunk size. For
////    FPlexes, chunk sizes represent maximum sizes.
////    Plex p(int size); /// Declares p to be an initially zero-sized Plex with low index of zero, and the indicated
////    chunk size. If size is negative, then the Plex is created with free space at the beginning of the Plex, allowing more efficient add_low() operations. Otherwise, it leaves space at the end.
////    Plex p(int low, int size); /// Declares p to be an initially zero-sized Plex with low index of low, and the
////    indicated chunk size.
////    Plex p(int low, int high, Base initval, int size = 0); /// Declares p to be a Plex with indices from low to high,
////    initially filled with initval, and the indicated chunk size if specified, else the default or (high - low + 1), whichever is greater.
////    Plex q(p); /// Declares q to be a copy of p.
////    p = q;
////    Copies Plex q into p, deleting its previous contents.
////    p.length() /// Returns the number of elements in the Plex.
//    fun length(): Int
//
//    //    p.empty() /// Returns true if Plex p contains no elements.
//    fun empty(): Boolean
//
//    //    p.full() /// Returns true if Plex p cannot be expanded. This always returns false for XPlexes and MPlexes.
//    fun full(): Boolean
//
//    //    p[i] /// Returns a reference to the i'th element of p. An exception (error) occurs if i is not a valid index.
//    fun get(i: Int): Pix<T>
//
//    //    p.valid(i) /// Returns true if i is a valid index into Plex p.
//    fun valid(i: Int): Boolean
//
//    //    p.low(); p.high(); /// Return the minimum (maximum) valid index of the Plex, or the high (low) fence if the plex
////    is empty.
//    fun low(): Int
//    fun high(): Int
//
//
//    //    p.ecnef(); p.fence(); /// Return the index one position past the minimum (maximum) valid index.
//    fun ecnef(): Int
//    fun fence(): Int
//
//    //    p.next(i); i = p.prev(i); /// Set i to the next (previous) index. This index may not be within bounds.
//    fun next(i: Int): Int
//
//    //    p(Pix<T>) /// returns a reference to the item at Pix pix.
//    fun get(pix: Pix<T>): Pix<T>
//
//    //    pix = p.first(); pix = p.last(); /// Return the minimum (maximum) valid Pix of the Plex, or 0 if the plex is
////    empty.
//    fun first():Pix<T>
//    fun last():Pix<T>
//
//    //    p.next(Pix<T>); p.prev(Pix<T>); /// set pix to the next (previous) Pix, or 0 if there is none.
//    fun next(pix: Pix<T>):Pix<T>
//    fun prev(pix: Pix<T>):Pix<T>
//
//    //    p.owns(Pix<T>) /// Returns true if the Plex contains the element associated with pix.
//    fun owns(pix: Pix<T>): Boolean
//
//    //    p.Pix_to_index(Pix<T>) /// If pix is a valid Pix to an element of the Plex, returns its corresponding index, else
////    raises an exception.
//    fun Pix_to_index(pix: Pix<T>): Int
//
//    //    ptr = p.index_to_Pix(i) /// if i is a valid index, returns a the corresponding Pix.
//    fun index_to_Pix(i: Int):Pix<T>
//
//    //    p.low_element(); p.high_element(); /// Return a reference to the element at the minimum (maximum) valid index. An
////    exception occurs if the Plex is empty.
//    fun low_element(): Pix<T>
//
//    //    p.can_add_low(); p.can_add_high(); /// Returns true if the plex can be extended one element downward (upward).
////    These always return true for XPlex and MPlex.
//    fun can_add_low(): Boolean
//    fun can_add_high(): Boolean
//
//    //    j = p.add_low(x); j = p.add_high(x); /// Extend the Plex by one element downward (upward). The new minimum
////    (maximum) index is returned.
//    fun add_low(x: Pix<T>): Int
//    fun add_high(x: Pix<T>): Int
//
//    //    j = p.del_low(); j = p.del_high() /// Shrink the Plex by one element on the low (high) end. The new minimum
////    (maximum) element is returned. An exception occurs if the Plex is empty.
//    fun del_low(): Int
//    fun del_high(): Int
//
//    //    p.append(q); /// Append all of Plex q to the high side of p.
//    fun append(q: Plex<T>)
//
//    //    p.prepend(q); /// Prepend all of q to the low side of p.
//    fun prepend(q: Plex<T>)
//
//    //    p.clear() /// Delete all elements, resetting p to a zero-sized Plex.
//    fun clear()
//
//    //    p.reset_low(i); /// Resets p to be indexed starting at low() = i. For example. if p were initially declared via
////    Plex p(0, 10, 0), and then re-indexed via p.reset_low(5), it could then be indexed from indices 5 .. 14.
//    fun reset_low(i: Int)
//
//    //    p.fill(x) /// sets all p[i] to x.
//    fun fill(x: Pix<T>)
//
//    //    p.fill(x, lo, hi) /// sets all of p[i] from lo to hi, inclusive, to x.
//    fun fill(x: Pix<T>, lo: Int, hi: Int)
//
//    //    p.reverse() /// reverses p in-place.
//    fun reverse()
//
//    //    p.chunk_size() /// returns the chunk size used for the plex.
//    fun chunk_size(): Int
//
//    //    p.error(const char * msg) /// calls the resettable error handler.
//    fun error(msg: String)
//}
//
////implements Plex<T> common features  for all Plexes
//class AbstractPlex <T>:Plex<T>{
//    //    Plex p(int size); /// Declares p to be an initially zero-sized Plex with low index of zero, and the indicated
////    chunk size. If size is negative, then the Plex is created with free space at the beginning of the Plex, allowing more efficient add_low() operations. Otherwise, it leaves space at the end.
////    Plex p(int low, int size); /// Declares p to be an initially zero-sized Plex with low index of low, and the
////    indicated chunk size.
////    Plex p(int low, int high, Base initval, int size = 0); /// Declares p to be a Plex with indices from low to high,
////    initially filled with initval, and the indicated chunk size if specified, else the default or (high - low + 1), whichever is greater.
////    Plex q(p); /// Declares q to be a copy of p.
////    p = q;
////    Copies Plex q into p, deleting its previous contents.
////    p.length() /// Returns the number of elements in the Plex.
//    override fun length(): Int {
//        return high() - low() + 1
//    }
//
//    //    p.empty() /// Returns true if Plex p contains no elements.
//    override fun empty(): Boolean {
//        return low() > high()
//    }
//
//    //    p.full() /// Returns true if Plex p cannot be expanded. This always returns false for XPlexes and MPlexes.
//    override fun full(): Boolean {
//        return false
//    }
//
//    //    p[i] /// Returns a reference to the i'th element of p. An exception (error) occurs if i is not a valid index.
//    override fun get(i: Int): Pix<T> {
//        return index_to_Pix(i)
//    }
//
//    //    p.valid(i) /// Returns true if i is a valid index into Plex p.
//    override fun valid(i: Int): Boolean {
//        return low() <= i && i <= high()
//    }
//
//    //    p.low(); p.high(); /// Return the minimum (maximum) valid index of the Plex, or the high (low) fence if the plex
////    is empty.
//    override fun low(): Int {
//        return _low
//    }
//
//    override fun high(): Int {
//        return _high
//    }
//
//    //    p.ecnef(); p.fence(); /// Return the high (low) fence of the Plex.
//    override fun ecnef(): Int {
//        return _ecnef
//    }
//
//    override fun fence(): Int {
//        return _fence
//    }
//
//    //    p.first(); p.last(); /// Return a Pix to the first (last) element of the Plex, or 0 if the Plex is
////    empty.
//    override fun first(): Pix<T> {
//        return if (empty()) Pix<T>() else index_to_Pix(low())
//    }
//
//    override fun last(): Pix<T> {
//        return if (empty()) Pix<T>() else index_to_Pix(high())
//    }
//
//    //    p.next(Pix<T>); p.prev(Pix<T>); /// set pix to the next (previous) Pix, or 0 if there is none.
//    override fun next(pix: Pix<T>): Pix<T> {
//        return if (pix.index() < high()) index_to_Pix(pix.index() + 1) else Pix<T>()
//    }
//
//    override fun prev(pix: Pix<T>): Pix<T> {
//        return if (pix.index() > low()) index_to_Pix(pix.index() - 1) else Pix<T>()
//    }
//
//    //    p.owns(Pix<T>) /// Returns true if the Plex contains the element associated with pix.
//    override fun owns(pix: Pix<T>): Boolean {
//        return valid(pix.index())
//    }
//
//    //    p.Pix_to_index(Pix<T>) /// If pix is a valid Pix to an element of the Plex, returns its corresponding index, else
////    returns -1.
//    override fun Pix_to_index(pix: Pix<T>): Int {
//        return if (owns(pix)) pix.index() else -1
//    }
//
//    //    p.index_to_Pix(int) /// Returns a Pix to the element of the Plex with the given index, or 0 if the index is
////    invalid.
//    override fun index_to_Pix(i: Int): Pix<T> {
//        return if (valid(i)) Pix<T>(i, this) else Pix<T>()
//    }
//
//    //    p.add_low(x); p.add_high(x) /// Expand the Plex by one element on the low (high) end, and set the new minimum
////    (maximum) element to x. An exception (error) occurs if the Plex is full.
//    override fun add_low(x: Pix<T>) {
//        if (full()) error("Plex is full")
//        if (low() > ecnef()) {
//            _low--
//            _ecnef--
//            _fence--
//            _data[_low] = x
//        } else {
//            _low--
//            _ecnef--
//            _data[_ecnef] = x
//        }
//    }
//
//    override fun add_high(x: Pix<T>) {
//        if (full()) error("Plex is full")
//        if (high() < fence()) {
//            _high++
//            _fence++
//            _data[_high] = x
//        } else {
//            _high++
//            _fence++
//            _data[_fence] = x
//        }
//    }
//
//    //    p.del_low(); p.del_high() /// Delete the minimum (maximum) element of the Plex. An exception (error) occurs if
////    the Plex is empty.
//    override fun del_low() {
//        if (empty()) error("Plex is empty")
//        if (low() > ecnef()) {
//            _low++
//            _ecnef++
//            _fence++
//        } else {
//            _low++
//            _ecnef++
//        }
//    }
//
//    override fun del_high() {
//        if (empty()) error("Plex is empty")
//        if (high() < fence()) {
//            _high--
//            _fence--
//        } else {
//            _high--
//            _fence--
//        }
//    }
//
//    //    p.clear() /// Deletes all elements of the Plex.
//    override fun clear() {
//        _low = 0
//        _high = -1
//        _ecnef = 0
//        _fence = -1
//    }
//
//    //    p.set_low(int); p.set_high(int) /// Sets the low (high) fence of the Plex to the given index. An exception
////    (error) occurs if the index is invalid.
//    override fun set_low(i: Int) {
//        if (!valid(i)) error("Invalid index")
//        _low = i
//    }
//
//    override fun set_high(i: Int) {
//        if (!valid(i)) error("Invalid index")
//        _high = i
//    }
//
//    //    p.set_fences(int, int) /// Sets the low and high fences of the Plex to the given indices. An exception
////    (error) occurs if either index is invalid.
//    override fun set_fences(low: Int, high: Int) {
//        if (!valid(low) || !valid(high)) error("Invalid index")
//        _low = low
//        _high = high
//    }
//
//    //    p.set_size(int) /// Sets the size of the Plex to the given number of elements. An exception (error) occurs if
////    the Plex cannot be expanded to the given size.
//    override fun set_size(n: Int) {
//        if (n < 0) error("Invalid size")
//        if (n > size()) {
//            if (n > capacity()) error("Plex cannot be expanded to the given size")
//            _high = _low + n - 1
//            _fence = _ecnef + n - 1
//        } else {
//            _high = _low + n - 1
//            _fence = _ecnef + n - 1
//        }
//    }
//
//    //    p.set_capacity(int) /// Sets the capacity of the Plex to the given number of elements. An exception (error)
////    occurs if the Plex cannot be expanded to the given capacity.
//    override fun set_capacity(n: Int) {
//        if (n < 0) error("Invalid capacity")
//        if (n > capacity()) {
//            if (n < size()) error("Plex cannot be expanded to the given capacity")
//            val new_data = Array<Pix<T>?>(n) { null }
//            for (i in 0 until size()) {
//                new_data[i] = _data[_low + i]
//            }
//            _data = new_data
//            _low = 0
//            _high = size() - 1
//            _ecnef = 0
//            _fence = capacity() - 1
//        }
//    }
//
//    //    p.set(int, x) /// Sets the element of the Plex with the given index to x. An exception (error) occurs if the
////    index is invalid.
//    override fun set(i: Int, x: Pix<T>) {
//        if (!valid(i)) error("Invalid index")
//        _data[i] = x
//    }
//
//    //    p.get(int) /// Returns the element of the Plex with the given index. An exception (error) occurs if the index
////    is invalid.
//    override fun get(i: Int): Pix<T> {
//        if (!valid(i)) error("Invalid index")
//        return _data[i]!!
//    }
//
//    //    p.get_low(); p.get_high() /// Returns the minimum (maximum) element of the Plex. An exception (error) occurs if
////    the Plex is empty.
//    override fun get_low(): Pix<T> {
//        if (empty()) error("Plex is empty")
//        return _data[_low]!!
//    }
//
//    override fun get_high(): Pix<T> {
//        if (empty()) error("Plex is empty")
//        return _data[_high]!!
//    }
//
//    //    p.get_fences() /// Returns the low and high fences of the Plex.
//    override fun get_fences(): Pair<Int, Int> {
//        return Pair(_low, _high)
//    }
//
//    //    p.get_size() /// Returns the number of elements in the Plex.
//    override fun get_size(): Int {
//        return _high - _low + 1
//    }
//
//    //    p.get_capacity() /// Returns the capacity of the Plex.
//    override fun get_capacity(): Int {
//        return _data.size
//    }
//
//    //    p.get_data() /// Returns the underlying array of the Plex.
//    override fun get_data(): Array<Pix<T>?> {
//        return _data
//    }
//
//    //    p.empty() /// Returns true if the Plex is empty.
//    override fun empty(): Boolean {
//        return _low > _high
//    }
//
//    //    p.full() /// Returns true if the Plex is full.
//    override fun full(): Boolean {
//        return _high - _low + 1 == _data.size
//    }
//
//    //    p.valid(int) /// Returns true if the given index is valid.
//    override fun valid(i: Int): Boolean {
//        return i >= _low && i <= _high
//    }
//
//    //    p.low() /// Returns the index of the minimum element of the Plex.
//    override fun low(): Int {
//        return _low
//    }
//
//    //    p.high() /// Returns the index of the maximum element of the Plex.
//    override fun high(): Int {
//        return _high
//    }
//
//    //    p.ecnef() /// Returns the index of the element of the Plex that is one less than the minimum element.
//    override fun ecnef(): Int {
//        return _ecnef
//    }
//
//    //    p.fence() /// Returns the index of the element of the Plex that is one more than the maximum element.
//    override fun fence(): Int {
//        return _fence
//    }
//
//    //    p.begin() /// Returns a Pix to the minimum element of the Plex.
//    override fun begin(): Pix<T {
//        return Pix(this, _low)
//    }
//
//    //    p.end() /// Returns a Pix to the element of the Plex that is one more than the maximum element.
//    override fun end(): Pix<T> {
//        return Pix(this, _fence + 1)
//    }
//
//    //    p.rbegin() /// Returns a Pix to the maximum element of the Plex.
//    override fun rbegin(): Pix<T> {
//        return Pix(this, _high)
//    }
//
//    //    p.rend() /// Returns a Pix to the element of the Plex that is one less than the minimum element.
//    override fun rend(): Pix<T> {
//        return Pix(this, _ecnef - 1)
//    }
//
//    //    p.insert(int, x) /// Inserts x into the Plex at the given index. An exception (error) occurs if the index is
////    invalid.
//    override fun insert(i: Int, x: Pix<T>) {
//        if (!valid(i)) error("Invalid index")
//        if (full()) error("Plex is full")
//        for (j in _high downTo i) {
//            _data[j + 1] = _data[j]
//        }
//        _data[i] = x
//        _high++
//        _fence++
//    }
//
//    //    p.erase(int) /// Erases the element of the Plex with the given index. An exception (error) occurs if the index
////    is invalid.
//    override fun erase(i: Int) {
//        if (!valid(i)) error("Invalid index")
//        for (j in i until _high) {
//            _data[j] = _data[j + 1]
//        }
//        _high--
//        _fence--
//    }
//
//    //    p.push_back(x) /// Inserts x at the end of the Plex. An exception (error) occurs if the Plex is full.
//    override fun push_back(x: Pix<T>) {
//        if (full()) error("Plex is full")
//        _data[++_high] = x
//        _fence++
//    }
//
//    //    p.pop_back() /// Erases the last element of the Plex. An exception (error) occurs if the Plex is empty.
//    override fun pop_back() {
//        if (empty()) error("Plex is empty")
//        _high--
//        _fence--
//    }
//
//    //    p.push_front(x) /// Inserts x at the beginning of the Plex. An exception (error) occurs if the Plex is full.
//    override fun push_front(x: Pix<T>) {
//        if (full()) error("Plex is full")
//        for (i in _high downTo _low) {
//            _data[i + 1] = _data[i]
//        }
//        _data[_low] = x
//        _high++
//        _fence++
//    }
//
//    //    p.pop_front() /// Erases the first element of the Plex. An exception (error) occurs if the Plex is empty.
//    override fun pop_front() {
//        if (empty()) error("Plex is empty")
//        for (i in _low until _high) {
//            _data[i] = _data[i + 1]
//        }
//        _high--
//        _fence--
//    }
//
//    //    p.clear() /// Removes all elements from the Plex.
//    override fun clear() {
//        _low = 0
//        _high = -1
//        _ecnef = -1
//        _fence = -1
//    }
//
//    //    p.swap(p) /// Swaps the contents of the Plex with the contents of the given Plex.
//    override fun swap(p: Plex<T>) {
//        when (p is AbstractPlex<T>) {
//                val temp = _data
//                    _data = p._data
//                    p . _data = temp
//                    val temp2 = _low
//                            _low = p . _low
//                            p._low = temp2
//                val temp3 = _high
//
//            _high = p._high
//                    p . _high = temp3
//                    val temp4 = _ecnef
//                            _ecnef = p . _ecnef
//                            p._ecnef = temp4
//                val temp5 = _fence
//
//            _fence = p._fence
//                    p . _fence = temp5
//        }
//    }
//
//}
//
////    MPlexes are plexes with bitmaps that allow items to be logically deleted and restored. They behave like other plexes, but also support the following additional and modified capabilities:
//class MPlex <T>: AbstractPlex <T> {
//
////    MPlex p; /// Declares p to be an initially zero-sized MPlex with low index of zero, and the default chunk size.
//
//    constructor(low: Int, size: Int) : super(low, size)
//
//    //    p.del_index(i); p.del_Pix(Pix<T>) /// logically deletes p[i] (p(Pix<T>)). After deletion, attempts to access p[i]
////    generate a error. Indexing via low(), high(), prev(), and next() skip the element. Deleting an element never changes the logical bounds of the plex.
//    fun del_index(i: Int)
//    fun del_Pix(pix: Pix<T>)
//
//    //    p.undel_index(i); p.undel_Pix(Pix<T>) /// logically undeletes p[i] (p(Pix<T>)).
//    fun undel_index(i: Int)
//    fun undel_Pix(pix: Pix<T>)
//    override fun length(): Int {
//                TODO("Not yet implemented")
//    }
//
//    override fun empty(): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun full(): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun get(i: Int): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun get(pix: Pix<T>): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun valid(i: Int): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun low(): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun high(): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun ecnef(): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun fence(): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun next(i: Int): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun next(pix: Pix<T>): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun first(): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun last(): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun index_to_Pix(i: Int): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun low_element(): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    override fun can_add_low(): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun can_add_high(): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun add_high(x: Pix<T>): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun add_low(x: Pix<T>): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun Pix_to_index(pix: Pix<T>): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun owns(pix: Pix<T>): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun prev(pix: Pix<T>): Pix<T> {
//        TODO("Not yet implemented")
//    }
//
//    //    p.del_low(); p.del_high() /// Delete the lowest (highest) undeleted element, resetting the logical bounds of the
////    plex to the next lowest (highest) undeleted index. Thus, MPlex del_low() and del_high() may shrink the bounds of the plex by more than one index.
//    fun del_low()
//    fun del_high()
//    override fun clear() {
//        TODO("Not yet implemented")
//    }
//
//    override fun reset_low(i: Int) {
//        TODO("Not yet implemented")
//    }
//
//    override fun reverse() {
//        TODO("Not yet implemented")
//    }
//
//    override fun chunk_size(): Int {
//        TODO("Not yet implemented")
//    }
//
//    override fun error(msg: String) {
//        TODO("Not yet implemented")
//    }
//
//    override fun fill(x: Pix<T>, lo: Int, hi: Int) {
//        TODO("Not yet implemented")
//    }
//
//    override fun fill(x: Pix<T>) {
//        TODO("Not yet implemented")
//    }
//
//    override fun prepend(q: Plex<T>) {
//        TODO("Not yet implemented")
//    }
//
//    override fun append(q: Plex<T>) {
//        TODO("Not yet implemented")
//    }
//
//    //    p.adjust_bounds() /// Resets the low and high bounds of the Plex to the indexes of the lowest and highest actual
////    undeleted elements.
//    fun adjust_bounds()
//
//    //    int i = p.add(x) /// Adds x in an unused index, if possible, else performs add_high.
//    fun add(x: Pix<T>): Int
//
//    //    p.count() /// returns the number of valid (undeleted) elements.
//    fun count(): Int
//
//    //    p.available() /// returns the number of available (deleted) indices.
//    fun available(): Int
//
//    //    int i = p.unused_index() /// returns the index of some deleted element, if one exists, else triggers an error. An
////    unused element may be reused via undel.
//    fun unused_index(): Int
//
//    //    pix = p.unused_Pix() /// returns the pix of some deleted element, if one exists, else 0. An unused element may be
////    reused via undel.
//    fun unused_Pix(): Pix<T>
//}
