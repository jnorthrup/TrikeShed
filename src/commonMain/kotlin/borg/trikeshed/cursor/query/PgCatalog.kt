package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * `pg_catalog` derived from the [Catalog]: the rows psql's `\dt`, `\d`, `\d <table>` (its eight queries at server
 * version 14), `\d+`, `\dt+`, `\di`, `\df`, `\du`, `\dp`, `\dn+`, `\l` and a driver's type lookups read, built with
 * [rowsCursor] on each scan so a table created after connect is listed. Relations this catalog has no objects for
 * (defaults, indexes, constraints, policies, inheritance, statistics, publications, rules, triggers, descriptions,
 * functions) answer their columns and no rows ([PgEmptyRelation]); the one role is the owner, the one collation
 * pg's `default`. Object ids: namespaces as pg numbers them (pg_catalog 11, information_schema 13, public 2200),
 * the heap access method 2 (views carry none), user tables by [Catalog.oid] of their names, types by their pg
 * OIDs ([PG_TYPES]). Not served: DBeaver's `c.*`, `ARRAY[…]` literals, SCRAM.
 */

/** A pg type the wire reports: its OID, name, length (-1 variable), pass-by-value, category and array OID. */
class PgType(val oid: Long, val name: String, val length: Long, val byValue: Boolean, val category: String, val array: Long, val element: Long = 0) {
    companion object {
        val byOid: Map<Long, PgType> by lazy { PG_TYPES.associateBy { it.oid } }
    }
}

val PG_TYPES: List<PgType> = listOf(
    PgType(16, "bool", 1, true, "B", 1000),
    PgType(17, "bytea", -1, false, "U", 1001),
    PgType(19, "name", 64, false, "S", 1003),
    PgType(20, "int8", 8, true, "N", 1016),
    PgType(21, "int2", 2, true, "N", 1005),
    PgType(23, "int4", 4, true, "N", 1007),
    PgType(25, "text", -1, false, "S", 1009),
    PgType(26, "oid", 4, true, "N", 1028),
    PgType(700, "float4", 4, true, "N", 1021),
    PgType(701, "float8", 8, true, "N", 1022),
    PgType(705, "unknown", -2, false, "X", 0),
    PgType(1009, "_text", -1, false, "A", 0, element = 25),
    PgType(1021, "_float4", -1, false, "A", 0, element = 700),
    PgType(1043, "varchar", -1, false, "S", 1015),
    PgType(1082, "date", 4, true, "D", 1182),
    PgType(1184, "timestamptz", 8, true, "D", 1185),
    PgType(3802, "jsonb", -1, false, "U", 3807),
)

/**
 * The pg type a column's memento reports, agreeing with [pgType]'s names: bigint 20, double precision 701, boolean 16,
 * text 25, vector as `_float4` 1021 (its cells are sent as that array), any other array as `_text` 1009, a column typed
 * from nothing (NULL) as text, else jsonb 3802.
 */
fun pgTypeOid(meta: ColumnMeta): Long = when {
    meta.type == IOMemento.IoNothing -> 25
    else -> when (pgType(meta)) {
        "vector" -> 1021
        "text[]" -> 1009
        "bigint" -> 20
        "double precision" -> 701
        "boolean" -> 16
        "text" -> 25
        else -> 3802
    }
}

const val NS_PG_CATALOG = 11L
const val NS_INFORMATION_SCHEMA = 13L
const val NS_PUBLIC = 2200L
const val AM_HEAP = 2L
const val OWNER_OID = 10L
const val DATABASE_OID = 5L
const val COLLATION_DEFAULT = 100L

private val PG_CLASS_COLUMNS = listOf(
    "oid", "relname", "relnamespace", "reltype", "relowner", "relam", "relfilenode", "reltablespace", "relpages", "reltuples",
    "reltoastrelid", "relhasindex", "relisshared", "relpersistence", "relkind", "relnatts", "relchecks", "relhasrules",
    "relhastriggers", "relhassubclass", "relrowsecurity", "relforcerowsecurity", "relispopulated", "relreplident",
    "relispartition", "reloftype", "relacl", "reloptions", "relpartbound",
)

private val PG_CLASS_TYPES = mapOf(
    "oid" to "int8", "relnamespace" to "int8", "reltype" to "int8", "relowner" to "int8", "relam" to "int8", "relfilenode" to "int8",
    "reltablespace" to "int8", "relpages" to "int8", "reltuples" to "float4", "reltoastrelid" to "int8", "relhasindex" to "bool",
    "relisshared" to "bool", "relnatts" to "int8", "relchecks" to "int8", "relhasrules" to "bool", "relhastriggers" to "bool",
    "relhassubclass" to "bool", "relrowsecurity" to "bool", "relforcerowsecurity" to "bool", "relispopulated" to "bool",
    "relispartition" to "bool", "reloftype" to "int8", "relacl" to "text", "reloptions" to "text", "relpartbound" to "text",
)

fun Catalog.pgClass(): ColumnCursor = rowsCursor(PG_CLASS_COLUMNS, tables().map { t ->
    val oid = oid(t)
    mapOf(
        "oid" to oid, "relname" to t.name, "relnamespace" to NS_PUBLIC, "reltype" to 0L, "relowner" to OWNER_OID, "relam" to if (t.writes != null) AM_HEAP else 0L,
        "relfilenode" to oid, "reltablespace" to 0L, "relpages" to 0L, "reltuples" to -1.0, "reltoastrelid" to 0L, "relhasindex" to false,
        "relisshared" to false, "relpersistence" to "p", "relkind" to if (t.writes != null) "r" else "v", "relnatts" to t.columns().size.toLong(),
        "relchecks" to 0L, "relhasrules" to false, "relhastriggers" to false, "relhassubclass" to false, "relrowsecurity" to false,
        "relforcerowsecurity" to false, "relispopulated" to true, "relreplident" to "d", "relispartition" to false, "reloftype" to 0L,
        "relacl" to null, "reloptions" to null, "relpartbound" to null,
    )
}, PG_CLASS_TYPES)

fun Catalog.pgNamespace(): ColumnCursor = rowsCursor(
    listOf("oid", "nspname", "nspowner", "nspacl"),
    listOf(NS_PG_CATALOG to "pg_catalog", NS_INFORMATION_SCHEMA to "information_schema", NS_PUBLIC to "public").map { (oid, name) ->
        mapOf("oid" to oid, "nspname" to name, "nspowner" to OWNER_OID, "nspacl" to null)
    },
    mapOf("oid" to "int8", "nspowner" to "int8", "nspacl" to "text"),
)

private val PG_ATTRIBUTE_COLUMNS = listOf(
    "attrelid", "attname", "atttypid", "attstattarget", "attlen", "attnum", "attndims", "attcacheoff", "atttypmod", "attbyval",
    "attalign", "attstorage", "attcompression", "attnotnull", "atthasdef", "atthasmissing", "attidentity", "attgenerated",
    "attisdropped", "attislocal", "attinhcount", "attcollation", "attacl", "attoptions", "attfdwoptions", "attmissingval",
)

private val PG_ATTRIBUTE_TYPES = mapOf(
    "attrelid" to "int8", "atttypid" to "int8", "attstattarget" to "int8", "attlen" to "int8", "attnum" to "int8", "attndims" to "int8",
    "attcacheoff" to "int8", "atttypmod" to "int8", "attbyval" to "bool", "attnotnull" to "bool", "atthasdef" to "bool",
    "atthasmissing" to "bool", "attisdropped" to "bool", "attislocal" to "bool", "attinhcount" to "int8", "attcollation" to "int8",
    "attacl" to "text", "attoptions" to "text", "attfdwoptions" to "text", "attmissingval" to "text",
)

fun Catalog.pgAttribute(): ColumnCursor = rowsCursor(PG_ATTRIBUTE_COLUMNS, tables().flatMap { t ->
    val relation = oid(t)
    t.columns().mapIndexed { c, meta ->
        val type = PgType.byOid.getValue(pgTypeOid(meta))
        mapOf(
            "attrelid" to relation, "attname" to meta.name.toString(), "atttypid" to type.oid, "attstattarget" to -1L,
            "attlen" to type.length, "attnum" to (c + 1).toLong(), "attndims" to if (type.element != 0L) 1L else 0L, "attcacheoff" to -1L,
            "atttypmod" to -1L, "attbyval" to type.byValue, "attalign" to if (type.length == 8L) "d" else if (type.length == 4L) "i" else if (type.length == 2L) "s" else if (type.length == 1L) "c" else "i",
            "attstorage" to if (type.length < 0) "x" else "p", "attcompression" to "", "attnotnull" to (c == 0 && meta.name.toString() == "id"),
            "atthasdef" to false, "atthasmissing" to false, "attidentity" to "", "attgenerated" to "", "attisdropped" to false, "attislocal" to true,
            "attinhcount" to 0L, "attcollation" to if (type.category == "S") 100L else 0L, "attacl" to null, "attoptions" to null,
            "attfdwoptions" to null, "attmissingval" to null,
        )
    }
}, PG_ATTRIBUTE_TYPES)

private val PG_TYPE_COLUMNS = listOf(
    "oid", "typname", "typnamespace", "typowner", "typlen", "typbyval", "typtype", "typcategory", "typispreferred", "typisdefined",
    "typdelim", "typrelid", "typelem", "typarray", "typalign", "typstorage", "typnotnull", "typbasetype", "typtypmod", "typndims", "typcollation",
)

private val PG_TYPE_TYPES = mapOf(
    "oid" to "int8", "typnamespace" to "int8", "typowner" to "int8", "typlen" to "int8", "typbyval" to "bool", "typispreferred" to "bool",
    "typisdefined" to "bool", "typrelid" to "int8", "typelem" to "int8", "typarray" to "int8", "typnotnull" to "bool", "typbasetype" to "int8",
    "typtypmod" to "int8", "typndims" to "int8", "typcollation" to "int8",
)

fun Catalog.pgTypes(): ColumnCursor = rowsCursor(PG_TYPE_COLUMNS, PG_TYPES.map { t ->
    mapOf(
        "oid" to t.oid, "typname" to t.name, "typnamespace" to NS_PG_CATALOG, "typowner" to OWNER_OID, "typlen" to t.length, "typbyval" to t.byValue,
        "typtype" to "b", "typcategory" to t.category, "typispreferred" to (t.oid == 25L || t.oid == 701L || t.oid == 16L), "typisdefined" to true,
        "typdelim" to ",", "typrelid" to 0L, "typelem" to t.element, "typarray" to t.array,
        "typalign" to if (t.length == 8L) "d" else if (t.length == 2L) "s" else if (t.length == 1L) "c" else "i",
        "typstorage" to if (t.length < 0) "x" else "p", "typnotnull" to false, "typbasetype" to 0L, "typtypmod" to -1L, "typndims" to 0L,
        "typcollation" to if (t.category == "S") 100L else 0L,
    )
}, PG_TYPE_TYPES)

fun Catalog.pgDatabase(): ColumnCursor = rowsCursor(
    listOf("oid", "datname", "datdba", "encoding", "datcollate", "datctype", "datistemplate", "datallowconn", "datconnlimit", "dattablespace", "datacl"),
    listOf(mapOf(
        "oid" to DATABASE_OID, "datname" to borg.trikeshed.parse.kursive.sql.SqlFunctions.DATABASE, "datdba" to OWNER_OID, "encoding" to 6L,
        "datcollate" to "C", "datctype" to "C", "datistemplate" to false, "datallowconn" to true, "datconnlimit" to -1L, "dattablespace" to 1663L, "datacl" to null,
    )),
    mapOf("oid" to "int8", "datdba" to "int8", "encoding" to "int8", "datistemplate" to "bool", "datallowconn" to "bool", "datconnlimit" to "int8", "dattablespace" to "int8", "datacl" to "text"),
)

fun Catalog.pgAm(): ColumnCursor = rowsCursor(
    listOf("oid", "amname", "amhandler", "amtype"),
    listOf(mapOf("oid" to AM_HEAP, "amname" to "heap", "amhandler" to 0L, "amtype" to "t")),
    mapOf("oid" to "int8", "amhandler" to "int8"),
)

/** The one role, the owner: what `\du` lists and what `pg_get_userbyid` names. */
fun Catalog.pgRoles(): ColumnCursor = rowsCursor(
    listOf("oid", "rolname", "rolsuper", "rolinherit", "rolcreaterole", "rolcreatedb", "rolcanlogin", "rolreplication", "rolconnlimit", "rolpassword", "rolvaliduntil", "rolbypassrls", "rolconfig"),
    listOf(mapOf(
        // not a superuser and no CREATE ROLE / DATABASE, as the session reports is_superuser off and the wire's DDL is CREATE / DROP TABLE
        "oid" to OWNER_OID, "rolname" to borg.trikeshed.parse.kursive.sql.SqlFunctions.OWNER, "rolsuper" to false, "rolinherit" to true, "rolcreaterole" to false,
        "rolcreatedb" to false, "rolcanlogin" to true, "rolreplication" to false, "rolconnlimit" to -1L, "rolpassword" to "********", "rolvaliduntil" to null,
        "rolbypassrls" to false, "rolconfig" to null,
    )),
    mapOf("oid" to "int8", "rolsuper" to "bool", "rolinherit" to "bool", "rolcreaterole" to "bool", "rolcreatedb" to "bool", "rolcanlogin" to "bool", "rolreplication" to "bool", "rolconnlimit" to "int8", "rolvaliduntil" to "text", "rolbypassrls" to "bool", "rolconfig" to "text"),
)

/** The one collation, pg's `default` (oid 100): what every text column and type here carries as its collation. */
fun Catalog.pgCollation(): ColumnCursor = rowsCursor(
    listOf("oid", "collname", "collnamespace", "collowner", "collprovider", "collisdeterministic", "collencoding", "collcollate", "collctype", "collversion"),
    listOf(mapOf(
        "oid" to COLLATION_DEFAULT, "collname" to "default", "collnamespace" to NS_PG_CATALOG, "collowner" to OWNER_OID, "collprovider" to "d",
        "collisdeterministic" to true, "collencoding" to -1L, "collcollate" to "", "collctype" to "", "collversion" to null,
    )),
    mapOf("oid" to "int8", "collnamespace" to "int8", "collowner" to "int8", "collisdeterministic" to "bool", "collencoding" to "int8", "collversion" to "text"),
)

/**
 * The pg_catalog relations this catalog has no rows for: no defaults, indexes, constraints, policies,
 * inheritance, extended statistics, publications, rules, triggers, descriptions or functions. psql's
 * describe queries read them, so each answers its columns (pg's names and types) and no rows.
 */
enum class PgEmptyRelation(vararg columns: Pair<String, String>) {
    PG_ATTRDEF("oid" to "int8", "adrelid" to "int8", "adnum" to "int8", "adbin" to "text"),
    PG_INDEX(
        "indexrelid" to "int8", "indrelid" to "int8", "indnatts" to "int8", "indnkeyatts" to "int8", "indisunique" to "bool", "indisprimary" to "bool",
        "indisexclusion" to "bool", "indimmediate" to "bool", "indisclustered" to "bool", "indisvalid" to "bool", "indcheckxmin" to "bool",
        "indisready" to "bool", "indislive" to "bool", "indisreplident" to "bool", "indkey" to "text", "indcollation" to "text", "indclass" to "text",
        "indoption" to "text", "indexprs" to "text", "indpred" to "text",
    ),
    PG_POLICY("oid" to "int8", "polname" to "text", "polrelid" to "int8", "polcmd" to "text", "polpermissive" to "bool", "polroles" to "text", "polqual" to "text", "polwithcheck" to "text"),
    PG_CONSTRAINT(
        "oid" to "int8", "conname" to "text", "connamespace" to "int8", "contype" to "text", "condeferrable" to "bool", "condeferred" to "bool",
        "convalidated" to "bool", "conrelid" to "int8", "contypid" to "int8", "conindid" to "int8", "conparentid" to "int8", "confrelid" to "int8",
        "confupdtype" to "text", "confdeltype" to "text", "confmatchtype" to "text", "conislocal" to "bool", "coninhcount" to "int8",
        "connoinherit" to "bool", "conkey" to "text", "confkey" to "text", "conbin" to "text",
    ),
    PG_INHERITS("inhrelid" to "int8", "inhparent" to "int8", "inhseqno" to "int8", "inhdetachpending" to "bool"),
    PG_STATISTIC_EXT("oid" to "int8", "stxrelid" to "int8", "stxname" to "text", "stxnamespace" to "int8", "stxowner" to "int8", "stxstattarget" to "int8", "stxkeys" to "text", "stxkind" to "text"),
    PG_PUBLICATION("oid" to "int8", "pubname" to "text", "pubowner" to "int8", "puballtables" to "bool", "pubinsert" to "bool", "pubupdate" to "bool", "pubdelete" to "bool", "pubtruncate" to "bool", "pubviaroot" to "bool"),
    PG_PUBLICATION_REL("oid" to "int8", "prpubid" to "int8", "prrelid" to "int8"),
    PG_REWRITE("oid" to "int8", "rulename" to "text", "ev_class" to "int8", "ev_type" to "text", "ev_enabled" to "text", "is_instead" to "bool", "ev_qual" to "text", "ev_action" to "text"),
    PG_TRIGGER(
        "oid" to "int8", "tgrelid" to "int8", "tgparentid" to "int8", "tgname" to "text", "tgfoid" to "int8", "tgtype" to "int8", "tgenabled" to "text",
        "tgisinternal" to "bool", "tgconstrrelid" to "int8", "tgconstrindid" to "int8", "tgconstraint" to "int8", "tgdeferrable" to "bool",
        "tginitdeferred" to "bool", "tgnargs" to "int8", "tgattr" to "text", "tgargs" to "text", "tgqual" to "text", "tgoldtable" to "text", "tgnewtable" to "text",
    ),
    PG_DESCRIPTION("objoid" to "int8", "classoid" to "int8", "objsubid" to "int8", "description" to "text"),
    PG_PROC(
        "oid" to "int8", "proname" to "text", "pronamespace" to "int8", "proowner" to "int8", "prolang" to "int8", "procost" to "float8", "prorows" to "float8",
        "provariadic" to "int8", "prosupport" to "text", "prokind" to "text", "prosecdef" to "bool", "proleakproof" to "bool", "proisstrict" to "bool",
        "proretset" to "bool", "provolatile" to "text", "proparallel" to "text", "pronargs" to "int8", "pronargdefaults" to "int8", "prorettype" to "int8",
        "proargtypes" to "text", "proallargtypes" to "text", "proargmodes" to "text", "proargnames" to "text", "proargdefaults" to "text",
        "protrftypes" to "text", "prosrc" to "text", "probin" to "text", "prosqlbody" to "text", "proconfig" to "text", "proacl" to "text",
    );

    val relname: String = name.lowercase()
    val declared: Map<String, String> = mapOf(*columns)

    fun cursor(): ColumnCursor = rowsCursor(declared.keys.toList(), emptyList(), declared)

    companion object {
        val byName: Map<String, PgEmptyRelation> = entries.associateBy { it.relname }
    }
}
