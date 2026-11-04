package borg.trikeshed.nlp

import borg.trikeshed.lib.*

/** Index-only semantic recognizers cannot interpret distinct Enhanced++ vertices sharing a source token. */
fun NlpSentence.hasCopiedNodes(): Boolean =
    dependencies.view.any { it.governorCopy != 0 || it.dependentCopy != 0 }
