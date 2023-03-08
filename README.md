# ++(Bike)Shed library

many things

## tldr 
this is the backbone of the json scanner and the fast-enough single-threaded database within trappings.
```kotlin 
interface Join<A, B> {    val a: A;   val b: B;
    operator fun component1(): A = a//destructuring 1&2
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b); ...}

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>
val <T> Series<T>.size: Int get() = a
/** index operator for Series*/
operator fun <T> Series<T>.get(i: Int): T = b(i)
[...] dozens of mix-ins and specializations

typealias JsElement = Join<Twin<Int>, Series<Int>> //(openIdx j closeIdx) j commaIdxs
typealias JsIndex = Join<Twin<Int>, Series<Char>> //(element j src)
typealias JsContext = Join<JsElement, Series<Char>> //(element j src)
typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>

typealias RowVec = Series2<Any, () -> RecordMeta>
/** Cursors are a columnar abstraction composed of Series of Joined value+meta pairs (RecordMeta) */
typealias Cursor = Series<RowVec>
 ```

## if you are still reading... I've also written ideas that describe (some) goals and ideals of the library:  

* [x] strongly immmutable Join aka Pair,Twin,Series aka Array,Series2, Cursors, are all typealiases of Join
    * extending the language through index and other operators happens as a sideeffect of testing new expression
      economies.

    - `myseries[4,3,2,1]` will provide a mapped Series in any order specified, even dupes. similar range indexes are
      available for other types
    - `myseries<T>[(T)->Boolean]` is a shorthand filter expression
    - "banana".toSeries() / 'n' would split series into s_['ba','a','a']
    - combine(Series...), and join(Cursor...) will concatenate and widen respectively with underlying binary-search
      index remapping on y,x axis respectively where Cursors are concerned.
    - a handful of nonstandard symbols are used to hint the code for a quick read
        * _l,_a,_s,_m util objects provide e.g. `_l[1,2,3]` for a kotlin List; s_[] is a Series
            * CharSeries is a Series<Char> with ByteBuffer token manipulation methods
            * a LongSeries<T> exists to enable 64 bit indexing e.g. file-IO random access virtualization and other large
              contiguous or sparse things
            * some symbol liberties:
                - `(myseries as Series<T>)` __α__ `{it:T-> foo(it)}` is an infix, lazy .map analog of a series
                - `(myseries as Series<T>).`\`▶\` visually noticable forward-iterator accessor denoting kotlin stdlib
                  collections/functional facade for a given purpose, typically filters, maps, or folds
                - left identity anchors, respectively __\`↺\`__ e.g. "columnname".\`↺\` to functionalize a constant or
                  other
                  value in situations where sometimes a lambda might be generative but constant can be distinctly picked
                  out
                  from in the code

* [x] Cursor lazy and memory-resident Dataframes lending strongly typed columns, with names, splittability,
  combinability, transforms,

* [x] ISAM Columnar Dataframes Storage @see http://github.com/jnorthrup/columnar
    - now with a native port. the jvm rewrite of columnar is also a full rewrite, streamlined and simplified.
    - the kotlin-native isam is linux-posix-64bit specific mmap code.
    - the columnar project has a lot more bells and whistles and is battle hardened
    - the default construction of an ISAM volume are tested to be correct in a single threaded environment
        - [x] the jvm version employs a lock-seek-reed-unlock strategy
        - [x] the native version uses [linux] `mmap` with readonly memory.
            - [x] in practice this is copmatible with macos posix until you look into liburing integration, so the uring
              attempt was made a seperate linux-only class from the IsamVolume
            - [ ] the posix code holds up well under mingw however the mmap calls are significantly different so this
              may warrant a seperate lock-seek-read-unlock strategy for windows, or someone with ambition to port the
              mmap calls

* [x] Duck-typing CSV-Cursor which includes varchar
  width sizing and narrowing numerical of types and float/integer detection on imported columns. supports
  explicit ISAM transcription on initial scan. heap stores only index to first records for CSV cursors.


* [x] JSON indexer/reifier/path-selector written for simplicity and speed. This is not a serdes library.
    * ~300 lines at time of writing this. no external deps. no reflection.
    * JsonParser.index(Series<Char>) will return a JsElement with CharSeries segments of the top level element.  
      each segment is the complete json object, array, or value including the open/close brackets to recreate
      the json and act as discriminators for the type of the segment.
        * optional depth list param will record how deep each segment is during the single-level scan of the input
        * optional field cutoff param will parse only the first n fields of the top level element. A very specific
          tabular use case drives this and the unparsed elements all come back in one abandoned segment with undefined
          behavior
    * JsonParser.reify(Series<Char>) parse and return the expression as nested maps and arrays and values
        * Js Arrays return as Series<Any?>, Js Objects return as Map<String,Any?> ; all Js Values return as Any?
        * for better or worse, non-string ParseDoubleOrNull not only does a cheap withotu string allocation costs but is
          also the source of parsed nulls when the Double parser falls through.
    * JsonParser.jsPath(Series<Char>,JsPath) ~~ghetto jq~~ will traverse the index to the depth of the path provided.
        * JsPathElement is an Either<String,Int> created by List<*>::toJsPath() extension function
        * optional reified param will return the value at the path reified as a kotlin type else just a segment JsIndex
        * String keys will abort on Arrays but Int keys will fetch the nth index from either a json object or Array


* [x] linux-biased Posix IO utils exist for kotlin-common, jvm, and native (linux only)
    * [ ] IO-Uring has been brought in and many tests ported, but not applied knowledgably as yet nor updated to keep
      current with liburing.


* [ ]  a handful of missing kotlin-common collections are scattered about, these would be about as warrantable as the
  unit
  tests you might find for them.