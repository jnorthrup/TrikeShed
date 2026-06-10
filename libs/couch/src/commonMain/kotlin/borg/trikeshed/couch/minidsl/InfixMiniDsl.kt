package borg.trikeshed.couch.minidsl

import borg.trikeshed.parse.confix.*

/**
 * Infix-only Confix surface.
 *
 * The object is used as an extension scope so the DSL remains explicit and does not
 * leak builder-style globals.
 */
object CouchConfixDsl {
    data class DesignRef(
        val database: String,
        val designDocument: String,
    )

    data class ViewRef(
        val database: String,
        val designDocument: String,
        val viewName: String,
    ) {
        val source: ConfixRelationRef
            get() = ConfixRelationRef(database = database, name = "$designDocument/$viewName", kind = ConfixRelationKind.VIEW)
    }

    typealias QueryRef = ConfixViewQueryPlan

    infix fun String.design(designDocument: String): DesignRef =
        DesignRef(database = this, designDocument = designDocument)

    infix fun DesignRef.view(viewName: String): ViewRef =
        ViewRef(database = database, designDocument = designDocument, viewName = viewName)

    infix fun ViewRef.whereKey(value: Any?): QueryRef =
        ConfixViewQueryPlan(
            source = source,
            designDocument = designDocument,
            viewName = viewName,
            parameters = mapOf("key" to value.toString()),
        )

    infix fun QueryRef.limit(limit: Int): QueryRef = withParameter("limit", limit)

    infix fun QueryRef.descending(descending: Boolean): QueryRef = withParameter("descending", descending)

    infix fun QueryRef.includeDocs(includeDocs: Boolean): QueryRef = withParameter("include_docs", includeDocs)

    val QueryRef.group: GroupRef
        get() = GroupRef(this.withParameter("group", true))

    val ViewRef.group: GroupRef
        get() = GroupRef(
            ConfixViewQueryPlan(
                source = source,
                designDocument = designDocument,
                viewName = viewName,
                parameters = mapOf("group" to true.toString()),
            ),
        )

    data class GroupRef(val query: QueryRef)

    infix fun GroupRef.level(level: Int): QueryRef =
        query.withParameter("group_level", level)
}

typealias DesignRef = CouchConfixDsl.DesignRef
typealias ViewRef = CouchConfixDsl.ViewRef
