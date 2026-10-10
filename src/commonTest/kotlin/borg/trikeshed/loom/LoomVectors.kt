package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.lib.*

/**
 * Golden vectors from the cocaine-rats loom mesh, branch codex/loom-launch-deployed-20261009 at e4898291,
 * printed by the loom-capture harness: crates/confix-rs and crates/loom-mesh compiled from that tree
 * (the private loom-mesh modules through #[path]). Seeds, times and nonces are fixed; a rerun prints this file.
 */
object LoomVectors {
    /** crates/confix-rs/src/item.rs encode: name j (Item j canonical bytes). */
    val item: Series<Join<String, Join<Item, String>>> = s_[
        "num_0" j (Item.Num(0L) j "00"),
        "num_23" j (Item.Num(23L) j "17"),
        "num_24" j (Item.Num(24L) j "1818"),
        "num_255" j (Item.Num(255L) j "18ff"),
        "num_256" j (Item.Num(256L) j "190100"),
        "num_65535" j (Item.Num(65535L) j "19ffff"),
        "num_65536" j (Item.Num(65536L) j "1a00010000"),
        "num_4294967295" j (Item.Num(4294967295L) j "1affffffff"),
        "num_4294967296" j (Item.Num(4294967296L) j "1b0000000100000000"),
        "num_i64_max" j (Item.Num(9223372036854775807L) j "1b7fffffffffffffff"),
        "neg_1" j (Item.Num(-1L) j "20"),
        "neg_24" j (Item.Num(-24L) j "37"),
        "neg_25" j (Item.Num(-25L) j "3818"),
        "neg_256" j (Item.Num(-256L) j "38ff"),
        "neg_257" j (Item.Num(-257L) j "390100"),
        "neg_65536" j (Item.Num(-65536L) j "39ffff"),
        "neg_65537" j (Item.Num(-65537L) j "3a00010000"),
        "neg_4294967296" j (Item.Num(-4294967296L) j "3affffffff"),
        "neg_4294967297" j (Item.Num(-4294967297L) j "3b0000000100000000"),
        "neg_i64_min" j (Item.Num(Long.MIN_VALUE) j "3b7fffffffffffffff"),
        "bin_empty" j (Item.Bin("".hexToByteArray()) j "40"),
        "bin_3" j (Item.Bin("010203".hexToByteArray()) j "43010203"),
        "bin_23" j (Item.Bin("000102030405060708090a0b0c0d0e0f10111213141516".hexToByteArray()) j "57000102030405060708090a0b0c0d0e0f10111213141516"),
        "bin_24" j (Item.Bin("000102030405060708090a0b0c0d0e0f1011121314151617".hexToByteArray()) j "5818000102030405060708090a0b0c0d0e0f1011121314151617"),
        "bin_256" j (Item.Bin("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".hexToByteArray()) j "590100000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
        "str_empty" j (Item.Str("") j "60"),
        "str_ascii" j (Item.Str("loom") j "646c6f6f6d"),
        "str_utf8" j (Item.Str("\u00fc\u20ac\ud83d\ude00") j "69c3bce282acf09f9880"),
        "str_24" j (Item.Str("abcdefghijklmnopqrstuvwx") j "78186162636465666768696a6b6c6d6e6f707172737475767778"),
        "arr_empty" j (itemArrayOf() j "80"),
        "arr_mixed" j (itemArrayOf(Item.Num(1L), Item.Str("a"), Item.Bin("00".hexToByteArray()), Item.Nil, Item.Bool(true), Item.Bool(false), Item.Flt(Double.fromBits(0x3ff8000000000000uL.toLong()))) j "870161614100f6f5f4fb3ff8000000000000"),
        "arr_24" j (itemArrayOf(Item.Num(0L), Item.Num(1L), Item.Num(2L), Item.Num(3L), Item.Num(4L), Item.Num(5L), Item.Num(6L), Item.Num(7L), Item.Num(8L), Item.Num(9L), Item.Num(10L), Item.Num(11L), Item.Num(12L), Item.Num(13L), Item.Num(14L), Item.Num(15L), Item.Num(16L), Item.Num(17L), Item.Num(18L), Item.Num(19L), Item.Num(20L), Item.Num(21L), Item.Num(22L), Item.Num(23L)) j "9818000102030405060708090a0b0c0d0e0f1011121314151617"),
        "map_empty" j (itemMapOf() j "a0"),
        "map_key_order" j (itemMapOf("b" to Item.Num(1L), "a" to Item.Num(2L), "aa" to Item.Num(3L), "B" to Item.Num(4L), "" to Item.Num(5L), "\u00fc" to Item.Num(6L), "z" to Item.Num(7L), "abcdefghijklmnopqrstuvwxy" to Item.Num(8L)) j "a86005614204616102616201617a076261610362c3bc0678196162636465666768696a6b6c6d6e6f7071727374757677787908"),
        "map_nested" j (itemMapOf("z" to itemMapOf("y" to itemArrayOf(Item.Num(1L), itemMapOf("x" to Item.Nil)), "a" to Item.Flt(Double.fromBits(0x8000000000000000uL.toLong()))), "m" to itemArrayOf()) j "a2616d80617aa26161fb800000000000000061798201a16178f6"),
        "map_duplicate_key" j (itemMapOf("k" to Item.Num(1L), "k" to Item.Num(2L)) j "a2616b01616b02"),
        "flt_0" j (Item.Flt(Double.fromBits(0x0000000000000000uL.toLong())) j "fb0000000000000000"),
        "flt_neg_0" j (Item.Flt(Double.fromBits(0x8000000000000000uL.toLong())) j "fb8000000000000000"),
        "flt_1" j (Item.Flt(Double.fromBits(0x3ff0000000000000uL.toLong())) j "fb3ff0000000000000"),
        "flt_1_5" j (Item.Flt(Double.fromBits(0x3ff8000000000000uL.toLong())) j "fb3ff8000000000000"),
        "flt_neg_2_5" j (Item.Flt(Double.fromBits(0xc004000000000000uL.toLong())) j "fbc004000000000000"),
        "flt_0_1" j (Item.Flt(Double.fromBits(0x3fb999999999999auL.toLong())) j "fb3fb999999999999a"),
        "flt_1e300" j (Item.Flt(Double.fromBits(0x7e37e43c8800759cuL.toLong())) j "fb7e37e43c8800759c"),
        "flt_min_subnormal" j (Item.Flt(Double.fromBits(0x0000000000000001uL.toLong())) j "fb0000000000000001"),
        "flt_nan" j (Item.Flt(Double.fromBits(0x7ff8000000000000uL.toLong())) j "fb7ff8000000000000"),
        "flt_inf" j (Item.Flt(Double.fromBits(0x7ff0000000000000uL.toLong())) j "fb7ff0000000000000"),
        "flt_neg_inf" j (Item.Flt(Double.fromBits(0xfff0000000000000uL.toLong())) j "fbfff0000000000000"),
        "bool_true" j (Item.Bool(true) j "f5"),
        "bool_false" j (Item.Bool(false) j "f4"),
        "nil" j (Item.Nil j "f6"),
        "tag_1" j (Item.Tag(1uL, Item.Num(1363896240L)) j "c11a514b67b0"),
        "tag_4294967296" j (Item.Tag(4294967296uL, Item.Nil) j "db0000000100000000f6"),
        "tag_u64_max" j (Item.Tag(18446744073709551615uL, Item.Str("t")) j "dbffffffffffffffff6174"),
    ]
    /** crates/confix-rs/src/item.rs decode then encode: name j (input j canonical bytes, or "error"). */
    val itemDecode: Series<Join<String, Twin<String>>> = s_[
        "f16_one" j ("f93c00" j "fb3ff0000000000000"),
        "f16_half" j ("f93800" j "fb3fe0000000000000"),
        "f16_min_subnormal" j ("f90001" j "fb3e70000000000000"),
        "f16_max" j ("f97bff" j "fb40effc0000000000"),
        "f16_neg_4" j ("f9c400" j "fbc010000000000000"),
        "f16_neg_0" j ("f98000" j "fb8000000000000000"),
        "f16_inf" j ("f97c00" j "fb7ff0000000000000"),
        "f16_neg_inf" j ("f9fc00" j "fbfff0000000000000"),
        "f16_nan" j ("f97e00" j "fb7ff8000000000000"),
        "f16_in_array" j ("82f93800f93c00" j "82fb3fe0000000000000fb3ff0000000000000"),
        "f32_1_5" j ("fa3fc00000" j "fb3ff8000000000000"),
        "f32_0_1" j ("fa3dcccccd" j "fb3fb99999a0000000"),
        "f32_inf" j ("fa7f800000" j "fb7ff0000000000000"),
        "indefinite_bytes" j ("5f4101420203ff" j "43010203"),
        "indefinite_text" j ("7f616161626163ff" j "63616263"),
        "indefinite_array" j ("9f0102ff" j "820102"),
        "indefinite_map" j ("bf616101616202ff" j "a2616101616202"),
        "head_24_small" j ("1805" j "05"),
        "head_25_small" j ("190005" j "05"),
        "array_head_wide" j ("980100" j "8100"),
        "map_unsorted" j ("a2616201616102" j "a2616102616201"),
        "simple_undefined" j ("f7" j "f6"),
        "u64_above_i64" j ("1b8000000000000000" j "error"),
        "neg_above_i64" j ("3b8000000000000000" j "error"),
        "utf8_invalid" j ("61ff" j "error"),
        "utf8_surrogate" j ("63eda080" j "error"),
        "utf8_overlong" j ("62c0af" j "error"),
        "text_truncated" j ("6261" j "error"),
        "bytes_length_wraps" j ("5b000000010000000401020304" j "error"),
        "simple_24" j ("f820" j "error"),
        "simple_0" j ("e0" j "error"),
        "additional_28" j ("1c" j "error"),
        "empty" j ("" j "error"),
        "array_truncated" j ("8201" j "error"),
        "tag_4294967296" j ("db0000000100000000f6" j "db0000000100000000f6"),
    ]
    /** crates/loom-mesh/src/codec.rs decode: name j (input hex, or sha256 of a built input j "ok" or the error). */
    val codec: Series<Join<String, Twin<String>>> = s_[
        "empty" j ("" j "CBOR size"),
        "zero" j ("00" j "ok"),
        "array_3" j ("83010203" j "ok"),
        "mixed" j ("8263616263420102" j "ok"),
        "i64_max" j ("1b7fffffffffffffff" j "ok"),
        "depth_16" j ("8181818181818181818181818181818100" j "ok"),
        "depth_17" j ("818181818181818181818181818181818100" j "CBOR complexity"),
        "trailing" j ("0000" j "CBOR trailing bytes"),
        "noncanonical_head" j ("1805" j "CBOR noncanonical"),
        "noncanonical_array" j ("980100" j "CBOR noncanonical"),
        "negative" j ("20" j "CBOR type"),
        "map" j ("a0" j "CBOR type"),
        "tag" j ("c100" j "CBOR type"),
        "true" j ("f5" j "CBOR type"),
        "null" j ("f6" j "CBOR type"),
        "float" j ("fb3ff0000000000000" j "CBOR type"),
        "indefinite_array" j ("9fff" j "CBOR indefinite or reserved"),
        "indefinite_bytes" j ("5fff" j "CBOR indefinite or reserved"),
        "reserved_28" j ("1c" j "CBOR indefinite or reserved"),
        "integer_range" j ("1b8000000000000000" j "CBOR integer range"),
        "array_head_range" j ("9b8000000000000000" j "CBOR integer range"),
        "bytes_over_wire" j ("5a0010c8e1" j "CBOR string size"),
        "bytes_truncated" j ("4200" j "CBOR truncated"),
        "head_truncated" j ("1901" j "CBOR truncated"),
        "array_truncated" j ("8200" j "CBOR truncated"),
        "utf8" j ("62c328" j "CBOR UTF8"),
        "array_size" j ("9a00010000" j "CBOR array size"),
        "items_4096" j ("sha256:7e29df35ec777d99f4e1746ef2fc4a2efd46744f4614874dfde9587b6b4f8be6" j "ok"),
        "items_4097" j ("sha256:84f193a30a5c9d878f3d835c1a6bbd553bf37e825f78db6e9c0c8c9d7365f8f9" j "CBOR array size"),
        "items_nested_4097" j ("sha256:ec5d7834074b98c24283455e05f28013e54a5e9c1c9ca0203dbb819054d5f186" j "CBOR array size"),
        "items_exhausted" j ("sha256:05cd2cdd2f1a6e39ab203ebdc940e8af5cff6c990c159e22f52a9bf99c1aa39c" j "CBOR complexity"),
        "wire_max" j ("sha256:d601f7c250f26293de074b6906936e973b7f207674467ee6e498d1c05d731db2" j "ok"),
        "wire_over" j ("sha256:fcf680705d0580c5e69df1dbdb8faac7e4e5317869bc080d3b61f98ea17b27b6" j "CBOR size"),
        "text_256" j ("sha256:ff52b61d7119986ee47df10f52d9dd52c3ed0aba3f98364301c17593dd9d5feb" j "ok"),
        "text_257" j ("sha256:927a91123ad0a4293fb8dd337d89434c11b9170a2123fc85c7a6179389411779" j "CBOR string size"),
    ]
    const val SEED_AUTHORITY = "0101010101010101010101010101010101010101010101010101010101010101"
    const val PUBLIC_AUTHORITY = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
    const val SEED_REPLICA_A = "0202020202020202020202020202020202020202020202020202020202020202"
    const val PUBLIC_REPLICA_A = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
    const val SEED_REPLICA_B = "0303030303030303030303030303030303030303030303030303030303030303"
    const val PUBLIC_REPLICA_B = "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1"
    const val SEED_REPLICA_C = "0404040404040404040404040404040404040404040404040404040404040404"
    const val PUBLIC_REPLICA_C = "ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c"
    const val SEED_PRODUCER = "0505050505050505050505050505050505050505050505050505050505050505"
    const val PUBLIC_PRODUCER = "6e7a1cdd29b0b78fd13af4c5598feff4ef2a97166e3ca6f2e4fbfccd80505bf1"
    /** crates/loom-mesh/src/membership.rs Member through serde_json: name j (json j "ok:" + serde_json output, or "error"). */
    val memberJson: Series<Join<String, Twin<String>>> = s_[
        "full" j ("{\"id\":\"replica-a\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"dc-a\",\"url\":\"https://replica-a.example/\",\"roles\":[\"replica\"]}" j "ok:{\"id\":\"replica-a\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"dc-a\",\"url\":\"https://replica-a.example/\",\"roles\":[\"replica\"]}"),
        "minimal" j ("{\"id\":\"authority\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"admin\"]}" j "ok:{\"id\":\"authority\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"\",\"url\":null,\"roles\":[\"admin\"]}"),
        "url_null" j ("{\"id\":\"reader\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"url\":null,\"roles\":[\"reader\"]}" j "ok:{\"id\":\"reader\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"\",\"url\":null,\"roles\":[\"reader\"]}"),
        "all_roles" j ("{\"roles\":[\"replica\",\"archive\",\"producer\",\"reader\",\"admin\"],\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"d\"}" j "ok:{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"d\",\"url\":null,\"roles\":[\"replica\",\"archive\",\"producer\",\"reader\",\"admin\"]}"),
        "empty_roles" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[]}" j "ok:{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"\",\"url\":null,\"roles\":[]}"),
        "whitespace" j ("{ \"id\" : \"x\" ,\u000a \"public_key\" : \"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\" , \"roles\" : [ \"admin\" ] }" j "ok:{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"\",\"url\":null,\"roles\":[\"admin\"]}"),
        "escaped_text" j ("{\"id\":\"a\\u002db\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"admin\"]}" j "ok:{\"id\":\"a-b\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":\"\",\"url\":null,\"roles\":[\"admin\"]}"),
        "unknown_field" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"admin\"],\"extra\":1}" j "error"),
        "missing_roles" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\"}" j "error"),
        "missing_id" j ("{\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"admin\"]}" j "error"),
        "missing_public_key" j ("{\"id\":\"x\",\"roles\":[\"admin\"]}" j "error"),
        "role_capitalized" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"Replica\"]}" j "error"),
        "role_unknown" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"owner\"]}" j "error"),
        "roles_not_array" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":\"admin\"}" j "error"),
        "domain_null" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"domain\":null,\"roles\":[\"admin\"]}" j "error"),
        "id_number" j ("{\"id\":7,\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"admin\"]}" j "error"),
        "url_number" j ("{\"id\":\"x\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"url\":7,\"roles\":[\"admin\"]}" j "error"),
        "duplicate_id" j ("{\"id\":\"x\",\"id\":\"y\",\"public_key\":\"8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394\",\"roles\":[\"admin\"]}" j "error"),
        "not_object" j ("[\"x\"]" j "error"),
    ]
    /** membership.rs Member::key: name j (public_key j "ok" or the error). */
    val memberKey: Series<Join<String, Twin<String>>> = s_[
        "valid" j ("8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394" j "ok"),
        "uppercase" j ("8139770EA87D175F56A35466C34C7ECCCB8D8A91B4EE37A25DF60F5B8FC9B394" j "public key encoding"),
        "short" j ("8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b3" j "public key encoding"),
        "long" j ("8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b39400" j "public key encoding"),
        "non_hex" j ("8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b39g" j "public key encoding"),
        "identity" j ("0100000000000000000000000000000000000000000000000000000000000000" j "weak public key"),
        "identity_signed" j ("0100000000000000000000000000000000000000000000000000000000000080" j "weak public key"),
        "order_2" j ("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" j "weak public key"),
        "order_4" j ("0000000000000000000000000000000000000000000000000000000000000000" j "weak public key"),
        "order_8" j ("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05" j "weak public key"),
        "order_8_signed" j ("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85" j "weak public key"),
        "order_8_other" j ("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a" j "weak public key"),
        "noncanonical_identity" j ("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" j "weak public key"),
        "noncanonical_zero" j ("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" j "weak public key"),
        "off_curve" j ("0200000000000000000000000000000000000000000000000000000000000000" j "public key"),
    ]
    /** membership.rs validate_custody_members over members() edited as named (see LoomMembershipTest): name j result. */
    val custodyMembers: Series<Twin<String>> = s_[
        "valid" j "ok",
        "duplicate_key" j "duplicate trust identity",
        "duplicate_id" j "duplicate trust identity",
        "duplicate_domain" j "duplicate trust domain",
        "replica_and_archive" j "invalid trust role",
        "no_roles" j "invalid trust role",
        "six_roles" j "invalid trust role",
        "bad_id" j "invalid label",
        "replica_without_domain" j "invalid label",
        "archive_without_domain" j "invalid label",
        "sixty_five" j "custody bounds",
    ]
    /** membership.rs valid_runpod_endpoint_origin: origin j result. */
    val runpodOrigin: Series<Join<String, Boolean>> = s_[
        "https://abc123.api.runpod.ai/" j true,
        "https://a-b.api.runpod.ai/" j true,
        "https://-abc.api.runpod.ai/" j false,
        "https://abc-.api.runpod.ai/" j false,
        "https://ABC.api.runpod.ai/" j false,
        "http://abc.api.runpod.ai/" j false,
        "https://.api.runpod.ai/" j false,
        "https://abc.api.runpod.ai" j false,
        "https://a_b.api.runpod.ai/" j false,
        "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.api.runpod.ai/" j true,
        "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.api.runpod.ai/" j false,
    ]
    const val T = 1790000000uL
    // crates/loom-mesh/src/segment.rs: Segment::sign("producer", 7, 42, pattern(48, 7, 3), SEED_PRODUCER)
    const val SEGMENT = "82856f6c6f6f6d2d7365676d656e742f76316870726f647563657207182a5830030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c58408628215903b4cf877d0d695f281c04cc57adf5a01c7445268a731059ece4f0308482810d0dabe1072dde3af1a46e785a5638cc9b01491cfcabfb766b51d31200"
    const val SEGMENT_ID = "a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1"
    val segmentErrors: Series<Twin<String>> = s_[
        "short_ciphertext" j "segment bounds",
        "bad_producer" j "invalid label",
        "epoch_range" j "segment bounds",
        "tampered_signature" j "segment signature",
        "unknown_producer" j "unknown producer",
        "wrong_tag" j "schema version",
    ]
    // crates/loom-mesh/src/auth.rs: Message::request_at("producer", "replica-a", "POST", "/v1/push", SEGMENT, SEED_PRODUCER, (T, NONCE_1)),
    // Receipt::issue_at("replica-a", SEGMENT_ID, NONCE_1, false, SEED_REPLICA_A, T + 1), Message::response_at(request, RECEIPT_PUSH, SEED_REPLICA_A, T + 1),
    // Message::request_at("authority", "replica-b", "GET", "/v1/status", [], SEED_AUTHORITY, (T + 2, NONCE_2))
    const val NONCE_1 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    const val NONCE_2 = "0104070a0d101316191c1f2225282b2e3134373a3d404346494c4f5255585b5e"
    const val MESSAGE_REQUEST = "82886f6c6f6f6d2d726571756573742f76316870726f6475636572697265706c6963612d6164504f5354682f76312f707573681a6ab13b805820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f589282856f6c6f6f6d2d7365676d656e742f76316870726f647563657207182a5830030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c58408628215903b4cf877d0d695f281c04cc57adf5a01c7445268a731059ece4f0308482810d0dabe1072dde3af1a46e785a5638cc9b01491cfcabfb766b51d312005840c35b074af5629980e4006dca73339f5b45a4c9cd1562a5fbea38da7d6d7211b6866bd6f8221d72a849ab83a0a39248239dc80dd2d0465c4ba3047cbabf892609"
    const val RECEIPT_PUSH = "82866f6c6f6f6d2d726563656970742f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c15820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f1a6ab13b810058405a0058b32c89517011b5958fcc786e90f0ccda44d564bc7f0524acc41e8046011cfa28051075023035d06b38bccad740d1fd58abbdb428ed29ecd3304ac9c105"
    const val MESSAGE_RESPONSE = "8288706c6f6f6d2d726573706f6e73652f7631697265706c6963612d616870726f647563657264504f5354682f76312f707573681a6ab13b815820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f58a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c15820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f1a6ab13b810058405a0058b32c89517011b5958fcc786e90f0ccda44d564bc7f0524acc41e8046011cfa28051075023035d06b38bccad740d1fd58abbdb428ed29ecd3304ac9c105584052284d684812340ba4cd63894eb2dcb83490bebb18db5c623989e198400f665b000ad1c822b4fdda3d79cc8f3af325016dec3063017322ca8297a47d1076360a"
    const val MESSAGE_GET = "82886f6c6f6f6d2d726571756573742f763169617574686f72697479697265706c6963612d62634745546a2f76312f7374617475731a6ab13b8258200104070a0d101316191c1f2225282b2e3134373a3d404346494c4f5255585b5e40584017444958f1a6c1faad087ed0510023ee1c75275d472739f2c0033ab1ff47a0211ccb74156a5695b5cd3bc2062157871135bf3ac21d05be4a716e67285f4a4104"
    const val MESSAGE_PAYLOAD_MAX_SHA256 = "f1e81318aa667a21c9caa0f0783281c4f1adec598d5187601cd860c29cf65730"
    /** auth.rs Message rejections, built as named in LoomAuthTest: name j error. */
    val messageErrors: Series<Twin<String>> = s_[
        "bad_path" j "message bounds",
        "bad_method" j "message bounds",
        "long_path" j "message bounds",
        "bad_source" j "invalid label",
        "wrong_version" j "message version",
        "payload_over" j "message bounds",
        "stale" j "message freshness",
        "future" j "message freshness",
        "edge_of_skew" j "ok",
        "response_as_request" j "message freshness",
        "tampered" j "message signature",
        "wrong_target" j "request binding",
        "wrong_peer" j "message signature",
        "response_other_nonce" j "response binding",
        "response_to_response" j "response binding",
        "clock_range" j "clock range",
    ]
    // crates/loom-mesh/src/custody.rs: Receipt::issue_at(replica, SEGMENT_ID, CHALLENGE, false, its seed, T + 3) for a, b, c;
    // Custody::verified_at(SEGMENT_ID, CHALLENGE, receipts, members, T + 4); CUSTODY_DEGRADED holds the first two receipts.
    const val CHALLENGE = "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d"
    const val RECEIPT_A = "82866f6c6f6f6d2d726563656970742f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b830058401f52cfef58d6d17f757d6ed8073d3799e140f4ea49e1ffe5b640d2d6f93c3d7210694883386c5b50c66b678445d5de83355a893e44756d08a2d9c7b7427a5d05"
    const val RECEIPT_B = "82866f6c6f6f6d2d726563656970742f7631697265706c6963612d625820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b830058406e8a33d9c02fcd773c0b1546523ad4b19b0df71b1539124eac0c72704821b8bd4be1f59c25d4cd45686f8040807e8d79ae14d082e14ab25210099579f2124308"
    const val RECEIPT_C = "82866f6c6f6f6d2d726563656970742f7631697265706c6963612d635820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b83005840f6d54610e3cec91624f9ddfd103a1074161b4f030fc23c5c7c3fffbf71eb024af6850b7e95bcf83cff58512cf588c7a9bbfb055f2b5ce55b1d76b15f121f850a"
    const val CUSTODY = "846f6c6f6f6d2d637573746f64792f76315820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d8358a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b830058401f52cfef58d6d17f757d6ed8073d3799e140f4ea49e1ffe5b640d2d6f93c3d7210694883386c5b50c66b678445d5de83355a893e44756d08a2d9c7b7427a5d0558a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d625820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b830058406e8a33d9c02fcd773c0b1546523ad4b19b0df71b1539124eac0c72704821b8bd4be1f59c25d4cd45686f8040807e8d79ae14d082e14ab25210099579f212430858a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d635820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b83005840f6d54610e3cec91624f9ddfd103a1074161b4f030fc23c5c7c3fffbf71eb024af6850b7e95bcf83cff58512cf588c7a9bbfb055f2b5ce55b1d76b15f121f850a"
    const val CUSTODY_DEGRADED = "846f6c6f6f6d2d637573746f64792f76315820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d8258a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b830058401f52cfef58d6d17f757d6ed8073d3799e140f4ea49e1ffe5b640d2d6f93c3d7210694883386c5b50c66b678445d5de83355a893e44756d08a2d9c7b7427a5d0558a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d625820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c1582002070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d1a6ab13b830058406e8a33d9c02fcd773c0b1546523ad4b19b0df71b1539124eac0c72704821b8bd4be1f59c25d4cd45686f8040807e8d79ae14d082e14ab25210099579f2124308"
    /** custody.rs and auth.rs Receipt rejections, built as named in LoomCustodyTest: name j error. */
    val custodyErrors: Series<Twin<String>> = s_[
        "duplicate_receipt" j "duplicate or archival custody",
        "unknown_signer" j "unknown receipt signer",
        "stale_receipt" j "receipt freshness",
        "wrong_challenge" j "custody binding",
        "wrong_id" j "custody binding",
        "receipt_kind" j "receipt kind",
        "receipt_wrong_id" j "receipt binding or role",
        "receipt_wrong_role" j "receipt binding or role",
        "receipt_wrong_nonce" j "receipt freshness",
        "receipt_wrong_key" j "receipt signature",
    ]
    // crates/loom-mesh/src/completion.rs: Completion{"authority", SEGMENT_ID, "pi_capture_0001", T + 10} signed by SEED_AUTHORITY
    const val COMPLETION = "8285781c6c6f6f6d2d736574746c656d656e742d636f6d706c657465642f763169617574686f726974795820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c16f70695f636170747572655f303030311a6ab13b8a5840df214e738e69ccd3b5daf9efd2ea7af004229081754e5e6901c3f8b3617b9e2e2e977bf0a6de7599962f15c79e21c0bf05662f6669c02478b358990794e8d208"
    const val COMPLETION_DIGEST = "22ba8d3c840513bb0e84f799c9c2792eee8aebd2082c90b120837b97fa859047"
    // crates/loom-mesh/src/settlement.rs: admission of COMPLETION by replica-a at T + 20 with receipts issued at T + 20 over NONCE_3
    const val NONCE_3 = "040d161f28313a434c555e677079828b949da6afb8c1cad3dce5eef70009121b"
    const val SETTLEMENT = "8285781c6c6f6f6d2d736574746c656d656e742d61646d697373696f6e2f763158a38285781c6c6f6f6d2d736574746c656d656e742d636f6d706c657465642f763169617574686f726974795820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c16f70695f636170747572655f303030311a6ab13b8a5840df214e738e69ccd3b5daf9efd2ea7af004229081754e5e6901c3f8b3617b9e2e2e977bf0a6de7599962f15c79e21c0bf05662f6669c02478b358990794e8d208697265706c6963612d611a6ab13b948358a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c15820040d161f28313a434c555e677079828b949da6afb8c1cad3dce5eef70009121b1a6ab13b9400584011451739cc60b8fdb105a817f033676952f41a0bab79e611e73b6d5e48784581c359e928274d845805baff05636cd709a13a16e2753993adb7d19065942a290058a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d625820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c15820040d161f28313a434c555e677079828b949da6afb8c1cad3dce5eef70009121b1a6ab13b940058404d987b63d76ddb92d90f90b8e49b068c027a54387e3e8e2ab5730ae5e4b852d7bb858fafcc11871c70940c575e30ebfb027dfed765fcaabe62123b7bd624600c58a882866f6c6f6f6d2d726563656970742f7631697265706c6963612d635820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c15820040d161f28313a434c555e677079828b949da6afb8c1cad3dce5eef70009121b1a6ab13b940058407d44974f7d1e32e55c72911a6dd71d5f5916847aac2aeac224b543027ec294e8a3e5fd8074bd5ab5545d9c17a719381862d528f4f411d62a205ab32d8a29ed085840eaa1213eddc0feac37c9edc20b98de4bec5740287ea8b0e8262639983a54d342222763178f3b0c8b88de0e8a773950d8970b13c999a631211be003cf0d97340a"
    const val BUNDLE = "83766c6f6f6d2d736574746c65642d6c65646765722f7631589282856f6c6f6f6d2d7365676d656e742f76316870726f647563657207182a5830030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c58408628215903b4cf877d0d695f281c04cc57adf5a01c7445268a731059ece4f0308482810d0dabe1072dde3af1a46e785a5638cc9b01491cfcabfb766b51d3120058a38285781c6c6f6f6d2d736574746c656d656e742d636f6d706c657465642f763169617574686f726974795820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c16f70695f636170747572655f303030311a6ab13b8a5840df214e738e69ccd3b5daf9efd2ea7af004229081754e5e6901c3f8b3617b9e2e2e977bf0a6de7599962f15c79e21c0bf05662f6669c02478b358990794e8d208"
    const val BUNDLE_NAME = "e78af71c5a0f10179025ff8f81d284ee2eef2543e18688721d2fd33ec75f5261.cbor"
    // Handoff::issue(config replica-a, SEGMENT, COMPLETION, GcsReceipt("loom-archive", "loom-settlement/v1/<sha256(BUNDLE)>.cbor", 1790000123456789, |BUNDLE|, sha256(BUNDLE)), SEED_REPLICA_A)
    const val HANDOFF = "828c736c6f6f6d2d6763732d68616e646f66662f7631697265706c6963612d615820a49891220c634cd2e175ff90e5aefd76d88f454c4149049469fdc9d4c67432c16870726f647563657207182a582022ba8d3c840513bb0e84f799c9c2792eee8aebd2082c90b120837b97fa8590476c6c6f6f6d2d6172636869766578586c6f6f6d2d736574746c656d656e742f76312f653738616637316335613066313031373930323566663866383164323834656532656566323534336531383638383732316432666433336563373566353236312e63626f721b00065bfee181ad151901515820e78af71c5a0f10179025ff8f81d284ee2eef2543e18688721d2fd33ec75f52615840c209e0a30020a87273c41160ed8309ff234c47785832ca8749c0251eedf337686bed677797f48ac7a22dd105629d133174f1f097d111f9120b55a9d17e4c770c"
    const val LIFECYCLE_HEADROOM_3 = 2250uL
    const val LIFECYCLE_HEADROOM_64 = 16281uL
    /** settlement.rs and completion.rs rejections, built as named in LoomSettlementTest: name j error. */
    val settlementErrors: Series<Twin<String>> = s_[
        "admission_quorum" j "admission quorum",
        "admission_custody" j "admission custody",
        "admission_wrong_id" j "completion authority or binding",
        "handoff_other_bucket" j "handoff binding",
        "handoff_other_node" j "handoff binding",
        "handoff_invalid_receipt" j "invalid GCS receipt",
        "completion_wrong_authority" j "completion authority or binding",
        "completion_reference_control" j "completion bounds",
    ]
    /** gcs.rs GcsReceipt::validate over the HANDOFF receipt edited as named: name j result. */
    val gcsReceipt: Series<Twin<String>> = s_[
        "valid" j "ok",
        "bucket_short" j "invalid GCS receipt",
        "bucket_upper" j "invalid GCS receipt",
        "bucket_dash" j "invalid GCS receipt",
        "bucket_64" j "invalid GCS receipt",
        "object_dotdot" j "invalid GCS receipt",
        "object_empty_part" j "invalid GCS receipt",
        "object_257" j "invalid GCS receipt",
        "object_256" j "ok",
        "generation_0" j "invalid GCS receipt",
        "generation_range" j "invalid GCS receipt",
        "size_0" j "invalid GCS receipt",
        "size_over" j "invalid GCS receipt",
    ]
    // crates/loom-mesh/src/ledger.rs: LedgerVersion::sign("authority", "customer-books", 1, [0; 32], T, "cumulative ledger state v1", SEED_AUTHORITY), then next(T + 900, "cumulative ledger state v2")
    const val LEDGER_V1 = "8287766c6f6f6d2d6c65646765722d76657273696f6e2f763169617574686f726974796e637573746f6d65722d626f6f6b7301582000000000000000000000000000000000000000000000000000000000000000001a6ab13b80581a63756d756c6174697665206c6564676572207374617465207631584068148685f9993ac79554f4871e3950e5e978dc7a4bfa106a17955eb9f0dadf9b8b14bfd4cb2f7faad8b5d23982816723ff01384942e9c929b82796883fde2905"
    const val LEDGER_V1_DIGEST = "a3e799d8e8f191cd02f483c657c842fb94bb18d28c6ae124ecc5019b880676c3"
    const val LEDGER_V2 = "8287766c6f6f6d2d6c65646765722d76657273696f6e2f763169617574686f726974796e637573746f6d65722d626f6f6b73025820a3e799d8e8f191cd02f483c657c842fb94bb18d28c6ae124ecc5019b880676c31a6ab13f04581a63756d756c6174697665206c65646765722073746174652076325840acd39a8e63a333aa73d2a3a988ea80b99fb4ba1ebc7ba0aafa333cdbb5ed18fba218c9cbe552289efffb47991c1d744f0e7d611a2947787f87a9b6991dedb607"
    const val LEDGER_V2_DIGEST = "5056876af0e9cc392fca601046f5b056e51d3b337ac5e36f17caf8094444ec7c"
    const val LEDGER_V1_EPOCH_START = 1789999200uL
    const val LEDGER_V1_WINDOW_OPEN = 1790000100uL
    const val LEDGER_V2_EPOCH_START = 1790000100uL
    const val LEDGER_V2_OBJECT_NAME = "ledger/customer-books/00000000000000000002.cbor"
    /** ledger.rs LedgerVersion::sign and verify, arguments as named in LoomLedgerTest: name j result. */
    val ledgerErrors: Series<Twin<String>> = s_[
        "first_with_previous" j "ledger version bounds",
        "later_without_previous" j "ledger version bounds",
        "version_0" j "ledger version bounds",
        "version_range" j "ledger version bounds",
        "issued_0" j "ledger version bounds",
        "issued_edge" j "ok",
        "issued_over_edge" j "ledger version bounds",
        "body_empty" j "ledger version bounds",
        "body_max" j "ok",
        "body_over" j "ledger version bounds",
        "bad_stream" j "invalid label",
        "wrong_authority" j "ledger signer",
        "tampered" j "ledger signature",
    ]
    const val LEDGER_EPOCH_SECS = 900uL
    const val LEDGER_DEADLINE_SECS = 1800uL
    /** crates/loom-mesh/src/lease.rs Lease::receipt through serde_json, one lease per provider (scopes and windows in LoomLeaseTest). */
    val leaseReceipt: Series<Twin<String>> = s_[
        "google" j "{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"google\",\"purpose\":\"archive\",\"enforcement\":\"provider\",\"scope\":\"loom-handoff@loom-capture.iam.gserviceaccount.com\",\"issued_at\":1790000000,\"not_after\":1790003600,\"key_id\":null}",
        "cloudflare" j "{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"cloudflare\",\"purpose\":\"archive\",\"enforcement\":\"provider\",\"scope\":\"loom-archive/settlement\",\"issued_at\":1790000000,\"not_after\":1790086400,\"key_id\":\"AKIDCAPTURE\"}",
        "vast" j "{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"vast\",\"purpose\":\"management\",\"enforcement\":\"revocation\",\"scope\":\"loom-capture\",\"issued_at\":1790000000,\"not_after\":1790007200,\"key_id\":\"4242\"}",
        "runpod" j "{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"runpod\",\"purpose\":\"invocation\",\"enforcement\":\"consumer\",\"scope\":\"https://abc123.api.runpod.ai/\",\"issued_at\":1790000000,\"not_after\":1790000600,\"key_id\":null}",
    ]
    /** lease.rs LeaseReceipt through serde_json: name j (json j "ok:" + serde_json output, or "error"). */
    val leaseReceiptJson: Series<Join<String, Twin<String>>> = s_[
        "no_key_id" j ("{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"google\",\"purpose\":\"archive\",\"enforcement\":\"provider\",\"scope\":\"s\",\"issued_at\":1790000000,\"not_after\":1790000060}" j "ok:{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"google\",\"purpose\":\"archive\",\"enforcement\":\"provider\",\"scope\":\"s\",\"issued_at\":1790000000,\"not_after\":1790000060,\"key_id\":null}"),
        "unknown_field" j ("{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"google\",\"purpose\":\"archive\",\"enforcement\":\"provider\",\"scope\":\"s\",\"issued_at\":1790000000,\"not_after\":1790000060,\"key_id\":null,\"secret\":\"x\"}" j "error"),
        "unknown_provider" j ("{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"gcp\",\"purpose\":\"archive\",\"enforcement\":\"provider\",\"scope\":\"s\",\"issued_at\":1790000000,\"not_after\":1790000060,\"key_id\":null}" j "error"),
        "missing_scope" j ("{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"vast\",\"purpose\":\"management\",\"enforcement\":\"revocation\",\"issued_at\":1790000000,\"not_after\":1790000060,\"key_id\":\"1\"}" j "error"),
        "negative_time" j ("{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"vast\",\"purpose\":\"management\",\"enforcement\":\"revocation\",\"scope\":\"s\",\"issued_at\":-1,\"not_after\":1,\"key_id\":\"1\"}" j "error"),
        "u64_max_time" j ("{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"vast\",\"purpose\":\"management\",\"enforcement\":\"revocation\",\"scope\":\"s\",\"issued_at\":18446744073709551615,\"not_after\":1,\"key_id\":\"1\"}" j "ok:{\"format\":\"loom-lease-receipt/v1\",\"provider\":\"vast\",\"purpose\":\"management\",\"enforcement\":\"revocation\",\"scope\":\"s\",\"issued_at\":18446744073709551615,\"not_after\":1,\"key_id\":\"1\"}"),
    ]
}
