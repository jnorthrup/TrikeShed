package borg.trikeshed.k8s.crd

import kotlinx.serialization.Serializable

@Serializable
data class ObjectMeta(
    val name: String,
    val namespace: String = "default",
    val resourceVersion: String? = null,
    val uid: String? = null
)

@Serializable
data class TrikeShedResourceSpec(
    val replicas: Int = 1,
    val image: String = "trikeshed:latest",
    val configMapName: String? = null,
    val connectToCcek: Boolean = true
)

@Serializable
data class TrikeShedResourceStatus(
    val phase: String = "Pending",
    val readyReplicas: Int = 0,
    val message: String? = null
)

@Serializable
data class TrikeShedResource(
    val apiVersion: String = "trikeshed.borg.io/v1alpha1",
    val kind: String = "TrikeShedResource",
    val metadata: ObjectMeta,
    val spec: TrikeShedResourceSpec,
    val status: TrikeShedResourceStatus? = null
)
