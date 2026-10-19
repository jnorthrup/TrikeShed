# TrikeShed — first-party attribution

Copyright (c) Glamdring Incorporated.

TrikeShed is licensed under the GNU Affero General Public License, version 3
(AGPLv3); see [LICENSE](LICENSE).

Existing contributor attributions and third-party copyright and license notices
remain in their respective files.

## Jeva inference port

Jeva: System One Models from First Principles
Copyright 2026 Qi Li and Xinchao Wang

The decision head, projections and inference conventions in `JevaDecision.kt`,
`JevaModel.kt` and `JevaInference.kt` adapt Jeva revision
`e2104e0720100f979f2cdd456a1cbbee96da0abd` from
<https://github.com/LiQiiiii/Jeva>. They have been ported to Kotlin commonMain,
Join/Series tensor oracles and bounded, mapped checkpoint storage.

`JevaBackbone.kt` adapts the Qwen3.5 text model in Transformers 5.17.0.
Copyright 2025 The Qwen Team and The HuggingFace Inc. team.
The port implements Float32 inference with per-layer recurrent/attention state.

The canonical combining classes in `JevaUnicodeData.kt` adapt the Unicode 9
table from `unicode-normalization-alignments` 0.1.12.
Copyright 2012–2018 The Rust Project Developers. Its Apache-2.0 option is used.
Other normalization and category tables were generated against tokenizers 0.23.3.

These upstream sources are licensed under the
[Apache License, Version 2.0](src/commonMain/resources/licenses/apache-2.0.txt).
This does not relicense model weights, upstream datasets or third-party
dependencies; consult their respective licenses and provenance.
