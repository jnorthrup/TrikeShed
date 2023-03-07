++(Bike)Shed library

many things

* [x] strongly immmutable Join aka Pair,Twin,Series aka Array,Series2, Cursors, are all typealiases of Join
    * nonstandard symbols are used to avoid collisions with stdlib
        * _l,_a,_s,_m util objects provide e.g. `_l[1,2,3]` for a kotlin List; s_[] is a Series
        * `mysereis α {it-> TODO()}` is an infix, lazy .map analog of a series
        * `myseries[4,3,2,1]` will provide a mapped Series in any order specified, even dupes. similar range
          indexes are available for other types
        * CharSeries is a Series<Char> with ByteBuffer token manipulation methods
        * a LongSeries<T> exists to enable 64 bit indexing e.g. file-IO random access virtualization and other large
          contiguous or sparse things


* [x] Isam Columnar DataTables @see http://github.com/jnorthrup/columnar
    - now with native. the jvm columnar is a full rewrite, simpler;
    - the kotlin-native isam is linux-posix-64bit specific mmap code.
    - the columnar project has a lot more bells and whistles and is battle hardened
    - ducktyping heapless CSV-parser aimed squarely at importing csv files into a columnar database; includes carvchar
      width sizing and narrowing numerical types and float/integer detection on imported columns


* [x] minimal json indexer/reifier/path selector written for simplicity and speed
    * ~300 lines. no external deps. no reflection. This is not a serdes library
    * JsonParser.index(Series<Char>) will return a JsElement with CharSeries segments of the top level element.  
      each segment is the complete json object, array, or value including the leading open/close brackets to recreate
      the json and act as disrciminators for the type of the segment.
        * optional depth list param will record how deep each segment is during the single-level scan of the input
        * optional field cutoff param will parse only the first n fields of the top level element. A very specific
          tabular usecase drives this and the unparsed elements all come back in abandoned segments with undefined
          behavior
    * JsonParser.reify(JsElement, Series<Char>) parse and return the expression as nested maps and arrays and values
        * Js Arrays return as Series<Any?>, Js Objects return as Map<String,Any?> ; all Js Values return as Any?
        * for better or worse, non-string ParseDoubleOrNull not only does a cheap withotu string allocation costs but is
          also the source of parsed nulls when the Double parser falls through.
    * JsonParser.jsPath(Series<Char>,JsPath) ~~ghetto jq~~ will traverse the index to the depth of the path provided.
        * JsPathElement is an Either<String,Int> created by List<*>::toJsPath() extension function
        * optional reified param will return the value at the path reified as a kotlin type else just a segment JsIndex
        * String keys will abort on Arrays but Int keys will fetch the nth index from either a json object or Array


* [x] linux-biased Posix IO utils exist for kotlin-common, jvm, and native (linux only)
    * IO-Uring has been brought in and many tests ported, but not applied knowledgably as yet nor updated to keep current with
      liburing.   


* [ ]  a handful of missing kotlin-common collections are scattered about, these would be about as warrantable as the unit
  tests you might find for them, tending towards none