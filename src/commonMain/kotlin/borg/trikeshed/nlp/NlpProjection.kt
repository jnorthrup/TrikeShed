package borg.trikeshed.nlp

import borg.trikeshed.lib.*

/** An unsupported semantic projection, independent of whether the raw parser evidence is valid. */
enum class NlpProjectionIssue { COPIED_NODE, UNREPORTED_IDENTITY }

/** Index-only semantic recognizers cannot interpret distinct Enhanced++ vertices sharing a source token. */
fun NlpSentence.hasCopiedNodes(): Boolean =
    dependencies.view.any { it.governorCopy != 0 || it.dependentCopy != 0 }

/**
 * Known historical CoreNLP projections omitted copy counts: default zero is not proof of their absence.
 * Other readers are not labelled invalid or assumed to be Stanford. Null establishes no general
 * correctness or calibrated confidence; callers still apply their own supported-fragment checks.
 */
fun NlpDocument.projectionIssue(sentence: NlpSentence): NlpProjectionIssue? {
    if (sentence.hasCopiedNodes()) return NlpProjectionIssue.COPIED_NODE
    val observed = metadata ?: return null
    val stanford = observed.processor == "edu.stanford.nlp.pipeline.StanfordCoreNLP" ||
        observed.implementation == "borg.trikeshed.graal.subvm.CoreNlpRuntime" || observed.runtime["module"] == "corenlp"
    return if (stanford && observed.runtime["dependencyNodeIdentity"] != "token-index+copy-count/1")
        NlpProjectionIssue.UNREPORTED_IDENTITY else null
}
