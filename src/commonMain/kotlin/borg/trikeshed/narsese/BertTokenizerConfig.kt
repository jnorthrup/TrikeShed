package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/** Admission of the local tokenizer metadata into the implemented uncased WordPiece rules. */
object BertTokenizerConfig {
    const val FILE_BYTES = 4_194_304L

    fun load(files: FileOperations, directory: String, config: BertConfig,
             channels: (String) -> FileChannel = { FileChannel.open(it, StandardOpenOption.READ) }): BertWordPiece {
        fun text(name: String): String {
            val path = files.resolvePath(directory, name)
            val channel = channels(path)
            var failure: Throwable? = null
            try {
                val size = channel.size()
                require(size in 1..FILE_BYTES) { "BERT tokenizer file exceeds four MiB: $path" }
                val bytes = ByteArray(size.toInt())
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    val position = buffer.position()
                    val remaining = buffer.remaining()
                    val n = channel.read(buffer, position.toLong())
                    require(n > 0 && n <= remaining && buffer.position() == position + n) {
                        "Truncated BERT tokenizer file: $path"
                    }
                }
                return bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (caught: Throwable) { failure = caught; throw caught }
            finally {
                try { channel.close() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
        val metadata = text("tokenizer_config.json")
        val serialized = if (files.isFile(files.resolvePath(directory, "tokenizer.json"))) text("tokenizer.json") else null
        val specialTokens = if (files.isFile(files.resolvePath(directory, "special_tokens_map.json")))
            text("special_tokens_map.json") else null
        if (files.isFile(files.resolvePath(directory, "added_tokens.json")))
            expect(objectMap(reifyStrict(text("added_tokens.json")), "added_tokens.json").isEmpty(), "no standalone added tokens")
        val tokenizer = BertWordPiece(text("vocab.txt"), minOf(512, config.maxPositionEmbeddings))
        validate(metadata, serialized, tokenizer, config, specialTokens)
        return tokenizer
    }

    /** [serialized] null: a vocab.txt-only checkpoint, admitted on tokenizer_config.json and the vocabulary alone. */
    fun validate(metadata: String, serialized: String?, tokenizer: BertWordPiece, config: BertConfig,
                 specialTokens: String? = null) {
        val options = objectMap(reifyStrict(metadata), "tokenizer_config.json")
        expect(options["do_lower_case"] == true, "do_lower_case=true")
        expect(options["model_max_length"] == null || number(options["model_max_length"], 512), "model_max_length=512")
        expect((options["tokenizer_class"] ?: "BertTokenizerFast") in listOf("BertTokenizer", "BertTokenizerFast"),
            "BertTokenizer class")
        expect((options["padding_side"] ?: "right") == "right", "right padding")
        expect((options["truncation_side"] ?: "right") == "right", "right truncation")
        expect((options["do_basic_tokenize"] ?: true) == true, "basic tokenization")
        expect((options["tokenize_chinese_chars"] ?: true) == true, "Chinese character splitting")
        expect(options["strip_accents"] == null || options["strip_accents"] == true, "accent stripping")
        expect(options["never_split"] == null || (options["never_split"] as? List<*>)?.isEmpty() == true,
            "only the five BERT special tokens are never split")
        expect(options["added_tokens_decoder"] == null, "added tokens declared in tokenizer.json")
        expect((options["split_special_tokens"] ?: false) == false, "unsplit BERT special tokens")
        expect(options["additional_special_tokens"] == null ||
            (options["additional_special_tokens"] as? List<*>)?.isEmpty() == true, "no additional special tokens")
        val names = mapOf("pad_token" to "[PAD]", "unk_token" to "[UNK]", "cls_token" to "[CLS]",
            "sep_token" to "[SEP]", "mask_token" to "[MASK]")
        for ((name, content) in names) if (name in options) expect(options[name] == content, name)
        if (specialTokens != null) for ((name, value) in objectMap(reifyStrict(specialTokens), "special_tokens_map.json")) {
            if (name == "additional_special_tokens") {
                expect(value is List<*> && value.isEmpty(), "no additional special tokens")
                continue
            }
            val content = names[name] ?: error("Unsupported BERT special token option: $name")
            if (value is String) expect(value == content, "special_tokens_map.json $name=$content")
            else {
                val token = objectMap(value, "special_tokens_map.json $name")
                expect(token["content"] == content && token["single_word"] == false && token["lstrip"] == false &&
                    token["rstrip"] == false && token["normalized"] == false, "special_tokens_map.json $name flags")
            }
        }

        if (serialized == null) {
            expect(tokenizer.rows == config.vocabSize, "vocabulary matches the model's embedding rows")
            expect(tokenizer.vocabulary["[PAD]"] == config.padTokenId, "pad_token_id matches [PAD]")
            for (content in names.values) requireNotNull(tokenizer.vocabulary[content]) { "Missing BERT special token $content" }
            return
        }
        val json = objectMap(reifyStrict(serialized), "tokenizer.json")
        expect(json["version"] == "1.0", "tokenizer.json version=1.0")
        expect(json["truncation"] == null && json["padding"] == null, "runtime-owned truncation and padding")
        val normalizer = objectMap(json["normalizer"], "normalizer")
        expect(normalizer["type"] == "BertNormalizer", "BertNormalizer")
        expect(normalizer["clean_text"] == true, "clean_text=true")
        expect(normalizer["handle_chinese_chars"] == true, "handle_chinese_chars=true")
        expect(normalizer["lowercase"] == true, "lowercase=true")
        expect(normalizer["strip_accents"] == null || normalizer["strip_accents"] == true, "strip_accents=true or null")
        expect(objectMap(json["pre_tokenizer"], "pre_tokenizer")["type"] == "BertPreTokenizer", "BertPreTokenizer")

        val model = objectMap(json["model"], "WordPiece model")
        expect(model["type"] == null || model["type"] == "WordPiece", "WordPiece model")
        expect(model["unk_token"] == "[UNK]", "unk_token=[UNK]")
        expect(model["continuing_subword_prefix"] == "##", "continuing_subword_prefix=##")
        expect(number(model["max_input_chars_per_word"], 100), "max_input_chars_per_word=100")
        val vocabulary = objectMap(model["vocab"], "WordPiece vocabulary")
        expect(tokenizer.vocabulary.size == config.vocabSize && vocabulary.size == config.vocabSize,
            "vocabulary matches the model's embedding rows")
        for ((word, id) in tokenizer.vocabulary) expect(number(vocabulary[word], id), "vocab.txt/tokenizer.json id for $word")
        expect(tokenizer.vocabulary["[PAD]"] == config.padTokenId, "pad_token_id matches [PAD]")

        val added = json["added_tokens"] as? List<*> ?: error("BERT tokenizer lacks added_tokens")
        expect(added.size == names.size, "exactly the five BERT added special tokens")
        val seen = mutableSetOf<String>()
        for (value in added) {
            val token = objectMap(value, "added token")
            val content = token["content"] as? String ?: error("BERT added token lacks content")
            expect(content in names.values && seen.add(content), "unique BERT special token $content")
            expect(number(token["id"], requireNotNull(tokenizer.vocabulary[content]) { "Missing BERT special token $content" }),
                "special token $content id")
            expect(token["special"] == true && token["single_word"] == false && token["lstrip"] == false &&
                token["rstrip"] == false && token["normalized"] == false, "special token $content flags")
        }

        val processor = objectMap(json["post_processor"], "post_processor")
        expect(processor["type"] == "TemplateProcessing", "TemplateProcessing")
        val single = processor["single"] as? List<*> ?: error("BERT tokenizer lacks single sequence template")
        expect(single.size == 3, "single sequence template [CLS] A [SEP]")
        for (index in 0..2) {
            val entry = objectMap(single[index], "single sequence template entry")
            val kind = if (index == 1) "Sequence" else "SpecialToken"
            expect(entry.size == 1 && kind in entry, "single sequence template entry $index")
            val value = objectMap(entry[kind], "single sequence template entry $index")
            expect(value["id"] == when (index) { 0 -> "[CLS]"; 1 -> "A"; else -> "[SEP]" } && number(value["type_id"], 0),
                "single sequence template id/type_id $index")
        }
        val specials = objectMap(processor["special_tokens"], "template special_tokens")
        for (content in listOf("[CLS]", "[SEP]")) {
            val token = objectMap(specials[content], "template $content")
            val ids = token["ids"] as? List<*>
            expect(token["id"] == content && token["tokens"] == listOf(content) && ids != null && ids.size == 1 &&
                number(ids[0], requireNotNull(tokenizer.vocabulary[content])), "template special token $content")
        }
        val decoder = objectMap(json["decoder"], "decoder")
        expect(decoder["type"] == "WordPiece" && decoder["prefix"] == "##", "WordPiece decoder prefix=##")
    }

    private fun objectMap(value: Any?, name: String): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "BERT tokenizer $name must be an object" }
        require(value.keys.toSet().size == value.size) { "BERT tokenizer $name contains duplicate keys" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun number(value: Any?, expected: Int): Boolean = (value as? Number)?.toDouble() == expected.toDouble()
    private fun expect(condition: Boolean, rule: String) = require(condition) { "Unsupported BERT tokenizer: requires $rule" }
}
