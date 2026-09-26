@file:JsModule("d3-quadtree")
@file:JsNonModule

package borg.trikeshed.web.d3

/** d3-quadtree, bundled into the vendored PatchForces alongside d3-force (its dependency). */
external fun quadtree(data: dynamic, x: (dynamic) -> Double, y: (dynamic) -> Double): dynamic
