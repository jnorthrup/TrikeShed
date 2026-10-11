package borg.trikeshed.job

import java.math.BigInteger
import java.security.*
import java.security.interfaces.*
import java.security.spec.*
import javax.crypto.KeyAgreement
import kotlin.random.Random
import kotlin.test.*

/**
 * [WeierstrassCurve] P-256 and P-384: the point-at-infinity.org NIST multiples of G (as RustCrypto p256 0.13.2 and
 * p384 0.13.1 carry them), NIST CAVP 186-4 SigVer (ring 0.17.14 ecdsa_verify_tests.txt), Project Wycheproof ECDSA,
 * and the JDK's ECDH and SHA256withECDSA / SHA384withECDSA over fresh keys. Kotlin/JS emulates Long, so the
 * scalar multiplications stay off the js test task.
 */
class WeierstrassCurveJvmTest {
    /** k, x, y with [k]G = (x, y): 1G to 20G, then the 32 large scalars. */
    val p256Multiples = listOf(
        Triple("1", "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
        Triple("2", "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978", "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"),
        Triple("3", "5ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c", "8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032"),
        Triple("4", "e2534a3532d08fbba02dde659ee62bd0031fe2db785596ef509302446b030852", "e0f1575a4c633cc719dfee5fda862d764efc96c3f30ee0055c42c23f184ed8c6"),
        Triple("5", "51590b7a515140d2d784c85608668fdfef8c82fd1f5be52421554a0dc3d033ed", "e0c17da8904a727d8ae1bf36bf8a79260d012f00d4d80888d1d0bb44fda16da4"),
        Triple("6", "b01a172a76a4602c92d3242cb897dde3024c740debb215b4c6b0aae93c2291a9", "e85c10743237dad56fec0e2dfba703791c00f7701c7e16bdfd7c48538fc77fe2"),
        Triple("7", "8e533b6fa0bf7b4625bb30667c01fb607ef9f8b8a80fef5b300628703187b2a3", "73eb1dbde03318366d069f83a6f5900053c73633cb041b21c55e1a86c1f400b4"),
        Triple("8", "62d9779dbee9b0534042742d3ab54cadc1d238980fce97dbb4dd9dc1db6fb393", "ad5accbd91e9d8244ff15d771167cee0a2ed51f6bbe76a78da540a6a0f09957e"),
        Triple("9", "ea68d7b6fedf0b71878938d51d71f8729e0acb8c2c6df8b3d79e8a4b90949ee0", "2a2744c972c9fce787014a964a8ea0c84d714feaa4de823fe85a224a4dd048fa"),
        Triple("a", "cef66d6b2a3a993e591214d1ea223fb545ca6c471c48306e4c36069404c5723f", "878662a229aaae906e123cdd9d3b4c10590ded29fe751eeeca34bbaa44af0773"),
        Triple("b", "3ed113b7883b4c590638379db0c21cda16742ed0255048bf433391d374bc21d1", "9099209accc4c8a224c843afa4f4c68a090d04da5e9889dae2f8eefce82a3740"),
        Triple("c", "741dd5bda817d95e4626537320e5d55179983028b2f82c99d500c5ee8624e3c4", "0770b46a9c385fdc567383554887b1548eeb912c35ba5ca71995ff22cd4481d3"),
        Triple("d", "177c837ae0ac495a61805df2d85ee2fc792e284b65ead58a98e15d9d46072c01", "63bb58cd4ebea558a24091adb40f4e7226ee14c3a1fb4df39c43bbe2efc7bfd8"),
        Triple("e", "54e77a001c3862b97a76647f4336df3cf126acbe7a069c5e5709277324d2920b", "f599f1bb29f4317542121f8c05a2e7c37171ea77735090081ba7c82f60d0b375"),
        Triple("f", "f0454dc6971abae7adfb378999888265ae03af92de3a0ef163668c63e59b9d5f", "b5b93ee3592e2d1f4e6594e51f9643e62a3b21ce75b5fa3f47e59cde0d034f36"),
        Triple("10", "76a94d138a6b41858b821c629836315fcd28392eff6ca038a5eb4787e1277c6e", "a985fe61341f260e6cb0a1b5e11e87208599a0040fc78baa0e9ddd724b8c5110"),
        Triple("11", "47776904c0f1cc3a9c0984b66f75301a5fa68678f0d64af8ba1abce34738a73e", "aa005ee6b5b957286231856577648e8381b2804428d5733f32f787ff71f1fcdc"),
        Triple("12", "1057e0ab5780f470defc9378d1c7c87437bb4c6f9ea55c63d936266dbd781fda", "f6f1645a15cbe5dc9fa9b7dfd96ee5a7dcc11b5c5ef4f1f78d83b3393c6a45a2"),
        Triple("13", "cb6d2861102c0c25ce39b7c17108c507782c452257884895c1fc7b74ab03ed83", "58d7614b24d9ef515c35e7100d6d6ce4a496716e30fa3e03e39150752bcecdaa"),
        Triple("14", "83a01a9378395bab9bcd6a0ad03cc56d56e6b19250465a94a234dc4c6b28da9a", "76e49b6de2f73234ae6a5eb9d612b75c9f2202bb6923f54ff8240aaa86f640b8"),
        Triple("000000000000000000000000000000000000000000000000018ebbb95eed0e13", "339150844ec15234807fe862a86be77977dbfb3ae3d96f4c22795513aeaab82f", "b1c14ddfdc8ec1b2583f51e85a5eb3a155840f2034730e9b5ada38b674336a21"),
        Triple("0000000000000000000000000000000000159d893d4cdd747246cdca43590e13", "1b7e046a076cc25e6d7fa5003f6729f665cc3241b5adab12b498cd32f2803264", "bfea79be2b666b073db69a2a241adab0738fe9d2dd28b5604eb8c8cf097c457b"),
        Triple("41ffc1fffffe01fffc0003fffe0007c001fff00003fff07ffe0007c000000003", "9eace8f4b071e677c5350b02f2bb2b384aae89d58aa72ca97a170572e0fb222f", "1bbdaec2430b09b93f7cb08678636ce12eaafd58390699b5fd2f6e1188fc2a78"),
        Triple("7fffffc03fffc003fffffc007fff00000000070000100000000e00fffffff3ff", "878f22cc6db6048d2b767268f22ffad8e56ab8e2dc615f7bd89f1e350500dd8d", "714a5d7bb901c9c5853400d12341a892ef45d87fc553786756c4f0c9391d763e"),
        Triple("0000fffff01ffff8ffffc00fffffffffc000000fffffc007fffffc000fffe3ff", "659a379625ab122f2512b8dada02c6348d53b54452dff67ac7ace4e8856295ca", "49d81ab97b648464d0b4a288bd7818fab41a16426e943527c4fed8736c53d0f6"),
        Triple("4000008000fffffc000003f00000ffffffff800003800f8000e0000e000000ff", "cbceaaa8a4dd44bbce58e8db7740a5510ec2cb7ea8da8d8f036b3fb04cda4de4", "4bd7aa301a80d7f59fd983fedbe59bb7b2863fe46494935e3745b360e32332fa"),
        Triple("003ffffff0001f80000003f80003ffffc0000000000ffe0000007ff818000f80", "f0c4a0576154ff3a33a3460d42eaed806e854dfa37125221d37935124ba462a4", "5b392fa964434d29eec6c9dbc261cf116796864aa2faadb984a2df38d1aef7a3"),
        Triple("000001c000000000001001f803ffffff80000000000007ff0000000000000000", "5e6c8524b6369530b12c62d31ec53e0288173bd662bdf680b53a41ecbcad00cc", "447fe742c2bfef4d0db14b5b83a2682309b5618e0064a94804e9282179fe089f"),
        Triple("7fc0007ffffffc0003fffffffffffffe00003fffff07ffffffffffffc007ffff", "03792e541bc209076a3d7920a915021ecd396a6eb5c3960024be5575f3223484", "fc774ae092403101563b712f68170312304f20c80b40c06282063db25f268de4"),
        Triple("7ffffc03ff807fffe0001fffff800fff800001ffff0001fffffe001fffc00000", "2379ff85ab693cdf901d6ce6f2473f39c04a2fe3dcd842ce7aab0e002095bcf8", "f8b476530a634589d5129e46f322b02fbc610a703d80875ee70d7ce1877436a1"),
        Triple("00fffffffe03fffc07fffc800070000fc0007ffc00000000000fffe1fbff81ff", "c1e4072c529bf2f44da769efc934472848003b3af2c0f5aa8f8ddbd53e12ed7c", "39a6ee77812bb37e8079cd01ed649d3830fca46f718c1d3993e4a591824abcdb"),
        Triple("01fff81fc000000000ff801fffc0f81f01fff8001fc005ffffff800000fffffc", "34dfbc09404c21e250a9b40fa8772897ac63a094877db65862b61bd1507b34f3", "cf6f8a876c6f99ceaec87148f18c7e1e0da6e165ffc8ed82abb65955215f77d3"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63253d", "83a01a9378395bab9bcd6a0ad03cc56d56e6b19250465a94a234dc4c6b28da9a", "891b64911d08cdcc5195a14629ed48a360ddfd4596dc0ab007dbf5557909bf47"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63253e", "cb6d2861102c0c25ce39b7c17108c507782c452257884895c1fc7b74ab03ed83", "a7289eb3db2610afa3ca18eff292931b5b698e92cf05c1fc1c6eaf8ad4313255"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63253f", "1057e0ab5780f470defc9378d1c7c87437bb4c6f9ea55c63d936266dbd781fda", "090e9ba4ea341a246056482026911a58233ee4a4a10b0e08727c4cc6c395ba5d"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632540", "47776904c0f1cc3a9c0984b66f75301a5fa68678f0d64af8ba1abce34738a73e", "55ffa1184a46a8d89dce7a9a889b717c7e4d7fbcd72a8cc0cd0878008e0e0323"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632541", "76a94d138a6b41858b821c629836315fcd28392eff6ca038a5eb4787e1277c6e", "567a019dcbe0d9f2934f5e4a1ee178df7a665ffcf0387455f162228db473aeef"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632542", "f0454dc6971abae7adfb378999888265ae03af92de3a0ef163668c63e59b9d5f", "4a46c11ba6d1d2e1b19a6b1ae069bc19d5c4de328a4a05c0b81a6321f2fcb0c9"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632543", "54e77a001c3862b97a76647f4336df3cf126acbe7a069c5e5709277324d2920b", "0a660e43d60bce8bbdede073fa5d183c8e8e15898caf6ff7e45837d09f2f4c8a"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632544", "177c837ae0ac495a61805df2d85ee2fc792e284b65ead58a98e15d9d46072c01", "9c44a731b1415aa85dbf6e524bf0b18dd911eb3d5e04b20c63bc441d10384027"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632545", "741dd5bda817d95e4626537320e5d55179983028b2f82c99d500c5ee8624e3c4", "f88f4b9463c7a024a98c7caab7784eab71146ed4ca45a358e66a00dd32bb7e2c"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632546", "3ed113b7883b4c590638379db0c21cda16742ed0255048bf433391d374bc21d1", "6f66df64333b375edb37bc505b0b3975f6f2fb26a16776251d07110317d5c8bf"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632547", "cef66d6b2a3a993e591214d1ea223fb545ca6c471c48306e4c36069404c5723f", "78799d5cd655517091edc32262c4b3efa6f212d7018ae11135cb4455bb50f88c"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632548", "ea68d7b6fedf0b71878938d51d71f8729e0acb8c2c6df8b3d79e8a4b90949ee0", "d5d8bb358d36031978feb569b5715f37b28eb0165b217dc017a5ddb5b22fb705"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632549", "62d9779dbee9b0534042742d3ab54cadc1d238980fce97dbb4dd9dc1db6fb393", "52a533416e1627dcb00ea288ee98311f5d12ae0a4418958725abf595f0f66a81"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254a", "8e533b6fa0bf7b4625bb30667c01fb607ef9f8b8a80fef5b300628703187b2a3", "8c14e2411fcce7ca92f9607c590a6fffac38c9cd34fbe4de3aa1e5793e0bff4b"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254b", "b01a172a76a4602c92d3242cb897dde3024c740debb215b4c6b0aae93c2291a9", "17a3ef8acdc8252b9013f1d20458fc86e3ff0890e381e9420283b7ac7038801d"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254c", "51590b7a515140d2d784c85608668fdfef8c82fd1f5be52421554a0dc3d033ed", "1f3e82566fb58d83751e40c9407586d9f2fed1002b27f7772e2f44bb025e925b"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254d", "e2534a3532d08fbba02dde659ee62bd0031fe2db785596ef509302446b030852", "1f0ea8a4b39cc339e62011a02579d289b103693d0cf11ffaa3bd3dc0e7b12739"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254e", "5ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c", "78cb9bf2b6670082c8b4f931e59b5d1327d54fcac7b047c265864ed85d82afcd"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254f", "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978", "f888aaee24712fc0d6c26539608bcf244582521ac3167dd661fb4862dd878c2e"),
        Triple("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550", "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", "b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a"),
    )

    val p384Multiples = listOf(
        Triple("1", "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7", "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f"),
        Triple("2", "08d999057ba3d2d969260045c55b97f089025959a6f434d651d207d19fb96e9e4fe0e86ebe0e64f85b96a9c75295df61", "8e80f1fa5b1b3cedb7bfe8dffd6dba74b275d875bc6cc43e904e505f256ab4255ffd43e94d39e22d61501e700a940e80"),
        Triple("3", "077a41d4606ffa1464793c7e5fdc7d98cb9d3910202dcd06bea4f240d3566da6b408bbae5026580d02d7e5c70500c831", "c995f7ca0b0c42837d0bbe9602a9fc998520b41c85115aa5f7684c0edc111eacc24abd6be4b5d298b65f28600a2f1df1"),
        Triple("4", "138251cd52ac9298c1c8aad977321deb97e709bd0b4ca0aca55dc8ad51dcfc9d1589a1597e3a5120e1efd631c63e1835", "cacae29869a62e1631e8a28181ab56616dc45d918abc09f3ab0e63cf792aa4dced7387be37bba569549f1c02b270ed67"),
        Triple("5", "11de24a2c251c777573cac5ea025e467f208e51dbff98fc54f6661cbe56583b037882f4a1ca297e60abcdbc3836d84bc", "8fa696c77440f92d0f5837e90a00e7c5284b447754d5dee88c986533b6901aeb3177686d0ae8fb33184414abe6c1713a"),
        Triple("6", "627be1acd064d2b2226fe0d26f2d15d3c33ebcbb7f0f5da51cbd41f26257383021317d7202ff30e50937f0854e35c5df", "09766a4cb3f8b1c21be6dda6c14f1575b2c95352644f774c99864f613715441604c45b8d84e165311733a408d3f0f934"),
        Triple("7", "283c1d7365ce4788f29f8ebf234edffead6fe997fbea5ffa2d58cc9dfa7b1c508b05526f55b9ebb2040f05b48fb6d0e1", "9475c99061e41b88ba52efdb8c1690471a61d867ed799729d9c92cd01dbd225630d84ede32a78f9e64664cdac512ef8c"),
        Triple("8", "1692778ea596e0be75114297a6fa383445bf227fbe58190a900c3c73256f11fb5a3258d6f403d5ece6e9b269d822c87d", "dcd2365700d4106a835388ba3db8fd0e22554adc6d521cd4bd1c30c2ec0eec196bade1e9cdd1708d6f6abfa4022b0ad2"),
        Triple("9", "8f0a39a4049bcb3ef1bf29b8b025b78f2216f7291e6fd3bac6cb1ee285fb6e21c388528bfee2b9535c55e4461079118b", "62c77e1438b601d6452c4a5322c3a9799a9b3d7ca3c400c6b7678854aed9b3029e743efedfd51b68262da4f9ac664af8"),
        Triple("a", "a669c5563bd67eec678d29d6ef4fde864f372d90b79b9e88931d5c29291238cced8e85ab507bf91aa9cb2d13186658fb", "a988b72ae7c1279f22d9083db5f0ecddf70119550c183c31c502df78c3b705a8296d8195248288d997784f6ab73a21dd"),
        Triple("b", "099056e27da7b998da1eeec2904816c57fe935ed5837c37456c9fd14892d3f8c4749b66e3afb81d626356f3b55b4ddd8", "2e4c0c234e30ab96688505544ac5e0396fc4eed8dfc363fd43ff93f41b52a3255466d51263aaff357d5dba8138c5e0bb"),
        Triple("c", "952a7a349bd49289ab3ac421dcf683d08c2ed5e41f6d0e21648af2691a481406da4a5e22da817cb466da2ea77d2a7022", "a0320faf84b5bc0563052deae6f66f2e09fb8036ce18a0ebb9028b096196b50d031aa64589743e229ef6bacce21bd16e"),
        Triple("d", "a567ba97b67aea5bafdaf5002ffcc6ab9632bff9f01f873f6267bcd1f0f11c139ee5f441abd99f1baaf1ca1e3b5cbce7", "de1b38b3989f3318644e4147af164ecc5185595046932ec086329be057857d66776bcb8272218a7d6423a12736f429cc"),
        Triple("e", "e8c8f94d44fbc2396bbeac481b89d2b0877b1dffd23e7dc95de541eb651cca2c41aba24dbc02de6637209accf0f59ea0", "891ae44356fc8ae0932bcbf6de52c8a933b86191e7728d79c8319413a09d0f48fc468ba05509de22d7ee5c9e1b67b888"),
        Triple("f", "b3d13fc8b32b01058cc15c11d813525522a94156fff01c205b21f9f7da7c4e9ca849557a10b6383b4b88701a9606860b", "152919e7df9162a61b049b2536164b1beebac4a11d749af484d1114373dfbfd9838d24f8b284af50985d588d33f7bd62"),
        Triple("10", "d5d89c3b5282369c5fbd88e2b231511a6b80dff0e5152cf6a464fa9428a8583bac8ebc773d157811a462b892401dafcf", "d815229de12906d241816d5e9a9448f1d41d4fc40e2a3bdb9caba57e440a7abad1210cb8f49bf2236822b755ebab3673"),
        Triple("11", "4099952208b4889600a5ebbcb13e1a32692befb0733b41e6dcc614e42e5805f817012a991af1f486caf3a9add9ffcc03", "5ecf94777833059839474594af603598163ad3f8008ad0cd9b797d277f2388b304da4d2faa9680ecfa650ef5e23b09a0"),
        Triple("12", "dfb1fe3a40f7ac9b64c41d39360a7423828b97cb088a4903315e402a7089fa0f8b6c2355169cc9c99dfb44692a9b93dd", "453aca1243b5ec6b423a68a25587e1613a634c1c42d2ee7e6c57f449a1c91dc89168b7036ec0a7f37a366185233ec522"),
        Triple("13", "8d481dab912bc8ab16858a211d750b77e07dbecca86cd9b012390b430467aabf59c8651060801c0e9599e68713f5d41b", "a1592ff0121460857be99f2a60669050b2291b68a1039aa0594b32fd7adc0e8c11ffba5608004e646995b07e75e52245"),
        Triple("14", "605508ec02c534bceee9484c86086d2139849e2b11c1a9ca1e2808dec2eaf161ac8a105d70d4f85c50599be5800a623f", "5158ee87962ac6b81f00a103b8543a07381b7639a3a65f1353aef11b733106dde92e99b78de367b48e238c38dad8eedd"),
        Triple("00000000000000000000000000000000000000000000000000000000000000000000000000000000018ebbb95eed0e13", "a499efe48839bc3abcd1c5cedbdd51904f9514db44f4686db918983b0c9dc3aee05a88b72433e9515f91a329f5f4fa60", "3b7ca28ef31f809c2f1ba24aaed847d0f8b406a4b8968542de139db5828ca410e615d1182e25b91b1131e230b727d36a"),
        Triple("000000000000000000000000000000000000000000000000000000000000000000159d893d4cdd747246cdca43590e13", "90a0b1cac601676b083f21e07bc7090a3390fe1b9c7f61d842d27fa315fb38d83667a11a71438773e483f2a114836b24", "3197d3c6123f0d6cd65d5f0de106fef36656cb16dc7cd1a6817eb1d51510135a8f492f72665cfd1053f75ed03a7d04c9"),
        Triple("41ffc1fffffe01fffc0003fffe0007c001fff00003fff07ffe0007c000000003ffffff807fff8007fffff800fffe0000", "f2a066bd332dc59bbc3d01da1b124c687d8bb44611186422de94c1da4ecf150e664d353ccdb5cb2652685f8eb4d2cd49", "d6ed0bf75fdd8e53d87765fa746835b673881d6d1907163a2c43990d75b454294f942ec571ad5aae1806caf2bb8e9a4a"),
        Triple("400000003803ffffffcfffffe0800000001ffffe03ffff1ffff801fffffffff8000001fffff800ffffff8001fffc7fff", "5c7f9845d1c4aa44747f9137b6f9c39b36b26b8a62e8af97290434d5f3b214f5a0131550adb19058dc4c8780c4165c4a", "712f7fccc86f647e70db8798228cb16344af3d00b139b6f8502939c2a965af0eb4e39e2e16ab8f597b8d5630a50c9d85"),
        Triple("4000008000fffffc000003f00000ffffffff800003800f8000e0000e000000ffffffe00000ffffffc0007e0000000fe0", "dd5838f7ec3b8acf1becfd746f8b668c577107e93548ed93ed0d254c112e76b10f053109ef8428bfcd50d38c4c030c57", "33244f479cdac34f160d9e4ce2d19d2ff0e3305b5bf0eef29e91e9de6e28f678c61b773aa7e3c03740e1a49d1aa2493c"),
        Triple("000000001ffc000000fff030001f0000fffff0000038000000000002003f007ffffff0000000000000ffe00000000000", "cb8ed893530bfba04b4ca655923aaad109a62bc8411d5925316c32d33602459c33057a1fbcb5f70aeb295d90f9165fbc", "426aee3e91b08420f9b357b66d5afcbcf3956590bf5564dbf9086042eb880493d19da39aaa6436c6b5fc66ce5596b43f"),
        Triple("000fffe0800001ff0001fffffff0000ffffffffffffff80000fffffc1fffffffffffff001ffffffffffffbffffe01ffc", "67f714012b6b070182122ddd435cc1c2262a1ab88939bc6a2906cb2b4137c5e82b4582160f6403cab887acdf5786a268", "90e31cf398ce2f8c5897c7380bf541075d1b4d3cb70547262b7095731252f181ac0597c66af8311c7780db39dec0bd32"),
        Triple("07fffe0001fffff800fff800001ffff0001fffffe001fffc0000003ffe03fffffff80ffff01ffff20001c0003f0001ff", "55a79df7b53a99d31462c7e1a5ed5623970715bb1021098cb973a7520cbd6365e613e4b2467486fb37e86e01cee09b8f", "b95aeb71693189911661b709a886a1867f056a0efe401ee11c06030e46f7a87731da4575863178012208707dd666727c"),
        Triple("0000003fff87effe07fe7ffffe07ffe07f0000000003fe007fff03e07c07ffe0007f0017fffffe000003fffff007ffff", "9539a968cf819a0e52e10eea3baca1b6480d7e4df69bc07002c568569047110ee4fe72fca423fdd5179d6e0e19c44844", "a7728f37a0ae0df2716061900d83a4da149144129f89a214a8260464bab609bb322e4e67de5e4c4c6cb8d25983ec19b0"),
        Triple("00ffffff0000007e07fffff80031ffeffdffffc3fffffffff01fffffffff80000000007fc000fc000000004003ffc000", "933fc13276672ab360d909161cd02d830b1628935df0d800c6ed602c59d575a86a8a97e3a2d697e3ed06be741c0097d6", "f35296bd7a6b4c6c025ed6d84338cccc7522a45c5d4fbdb1442556caefb598128fa188793ada510eb5f44e90a4e4bef1"),
        Triple("00001fff803ff8000001fff000ffffffe3fff000000007ffdff80000003fff007fffffc01ffffff9ffffe001fc000000", "0ce31e1c4a937071e6ebaca026a93d783848bcc0c1585daf639518125fcd1f1629d63041abfb11ffc8f03fa8b6fcf6bf", "a69ea55be4beab2d5224050febffbdfcfd614624c3b4f228909eb80012f003756d1c377e52f04fa539237f24dd080e2e"),
        Triple("7ff0001fff000fffffffff80007ffc0003f80001fff8000000001fc000000fff000000007ffc01fffc020000003fc000", "6842cfe3589ac268818291f31d44177a9168dcbc19f321ed66d81ecf59e31b54cca0ddfd4c4136780171748d69a91c54", "e3a5ecd5ac725f13dbc631f358c6e817edcf3a613b83832741a9db591a0bae767fc714f70c2e7ea891e4312047deccc0"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5295f", "605508ec02c534bceee9484c86086d2139849e2b11c1a9ca1e2808dec2eaf161ac8a105d70d4f85c50599be5800a623f", "aea7117869d53947e0ff5efc47abc5f8c7e489c65c59a0ecac510ee48ccef92116d16647721c984b71dc73c825271122"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52960", "8d481dab912bc8ab16858a211d750b77e07dbecca86cd9b012390b430467aabf59c8651060801c0e9599e68713f5d41b", "5ea6d00fedeb9f7a841660d59f996faf4dd6e4975efc655fa6b4cd028523f172ee0045a8f7ffb19b966a4f828a1addba"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52961", "dfb1fe3a40f7ac9b64c41d39360a7423828b97cb088a4903315e402a7089fa0f8b6c2355169cc9c99dfb44692a9b93dd", "bac535edbc4a1394bdc5975daa781e9ec59cb3e3bd2d118193a80bb65e36e2366e9748fb913f580c85c99e7bdcc13add"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52962", "4099952208b4889600a5ebbcb13e1a32692befb0733b41e6dcc614e42e5805f817012a991af1f486caf3a9add9ffcc03", "a1306b8887ccfa67c6b8ba6b509fca67e9c52c07ff752f32648682d880dc774bfb25b2cf55697f13059af10b1dc4f65f"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52963", "d5d89c3b5282369c5fbd88e2b231511a6b80dff0e5152cf6a464fa9428a8583bac8ebc773d157811a462b892401dafcf", "27eadd621ed6f92dbe7e92a1656bb70e2be2b03bf1d5c42463545a81bbf585442edef3460b640ddc97dd48ab1454c98c"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52964", "b3d13fc8b32b01058cc15c11d813525522a94156fff01c205b21f9f7da7c4e9ca849557a10b6383b4b88701a9606860b", "ead6e618206e9d59e4fb64dac9e9b4e411453b5ee28b650b7b2eeebc8c2040257c72db064d7b50af67a2a773cc08429d"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52965", "e8c8f94d44fbc2396bbeac481b89d2b0877b1dffd23e7dc95de541eb651cca2c41aba24dbc02de6637209accf0f59ea0", "76e51bbca903751f6cd4340921ad3756cc479e6e188d728637ce6bec5f62f0b603b9745eaaf621dd2811a362e4984777"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52966", "a567ba97b67aea5bafdaf5002ffcc6ab9632bff9f01f873f6267bcd1f0f11c139ee5f441abd99f1baaf1ca1e3b5cbce7", "21e4c74c6760cce79bb1beb850e9b133ae7aa6afb96cd13f79cd641fa87a82988894347c8dde75829bdc5ed9c90bd633"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52967", "952a7a349bd49289ab3ac421dcf683d08c2ed5e41f6d0e21648af2691a481406da4a5e22da817cb466da2ea77d2a7022", "5fcdf0507b4a43fa9cfad215190990d1f6047fc931e75f1446fd74f69e694af1fce559b9768bc1dd610945341de42e91"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52968", "099056e27da7b998da1eeec2904816c57fe935ed5837c37456c9fd14892d3f8c4749b66e3afb81d626356f3b55b4ddd8", "d1b3f3dcb1cf5469977afaabb53a1fc6903b1127203c9c02bc006c0be4ad5cd9ab992aec9c5500ca82a2457fc73a1f44"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52969", "a669c5563bd67eec678d29d6ef4fde864f372d90b79b9e88931d5c29291238cced8e85ab507bf91aa9cb2d13186658fb", "567748d5183ed860dd26f7c24a0f132208fee6aaf3e7c3ce3afd20873c48fa56d6927e69db7d77266887b09648c5de22"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5296a", "8f0a39a4049bcb3ef1bf29b8b025b78f2216f7291e6fd3bac6cb1ee285fb6e21c388528bfee2b9535c55e4461079118b", "9d3881ebc749fe29bad3b5acdd3c56866564c2835c3bff39489877ab51264cfc618bc100202ae497d9d25b075399b507"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5296b", "1692778ea596e0be75114297a6fa383445bf227fbe58190a900c3c73256f11fb5a3258d6f403d5ece6e9b269d822c87d", "232dc9a8ff2bef957cac7745c24702f1ddaab52392ade32b42e3cf3d13f113e594521e15322e8f729095405cfdd4f52d"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5296c", "283c1d7365ce4788f29f8ebf234edffead6fe997fbea5ffa2d58cc9dfa7b1c508b05526f55b9ebb2040f05b48fb6d0e1", "6b8a366f9e1be47745ad102473e96fb8e59e2798128668d62636d32fe242dda8cf27b120cd5870619b99b3263aed1073"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5296d", "627be1acd064d2b2226fe0d26f2d15d3c33ebcbb7f0f5da51cbd41f26257383021317d7202ff30e50937f0854e35c5df", "f68995b34c074e3de41922593eb0ea8a4d36acad9bb088b36679b09ec8eabbe8fb3ba4717b1e9acee8cc5bf82c0f06cb"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5296e", "11de24a2c251c777573cac5ea025e467f208e51dbff98fc54f6661cbe56583b037882f4a1ca297e60abcdbc3836d84bc", "705969388bbf06d2f0a7c816f5ff183ad7b4bb88ab2a211773679acc496fe513ce889791f51704cce7bbeb55193e8ec5"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc5296f", "138251cd52ac9298c1c8aad977321deb97e709bd0b4ca0aca55dc8ad51dcfc9d1589a1597e3a5120e1efd631c63e1835", "35351d679659d1e9ce175d7e7e54a99e923ba26e7543f60c54f19c3086d55b22128c7840c8445a96ab60e3fe4d8f1298"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52970", "077a41d4606ffa1464793c7e5fdc7d98cb9d3910202dcd06bea4f240d3566da6b408bbae5026580d02d7e5c70500c831", "366a0835f4f3bd7c82f44169fd5603667adf4be37aeea55a0897b3f123eee1523db542931b4a2d6749a0d7a0f5d0e20e"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52971", "08d999057ba3d2d969260045c55b97f089025959a6f434d651d207d19fb96e9e4fe0e86ebe0e64f85b96a9c75295df61", "717f0e05a4e4c312484017200292458b4d8a278a43933bc16fb1afa0da954bd9a002bc15b2c61dd29eafe190f56bf17f"),
        Triple("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52972", "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7", "c9e821b569d9d390a26167406d6d23d6070be242d765eb831625ceec4a0f473ef59f4e30e2817e6285bce2846f15f1a0"),
    )

    fun curve(name: String) = when (name) {
        "P-256" -> WeierstrassCurve.P256
        "P-384" -> WeierstrassCurve.P384
        else -> null
    }

    @Test fun nistMultiples() {
        for ((curve, multiples) in listOf(WeierstrassCurve.P256 to p256Multiples, WeierstrassCurve.P384 to p384Multiples))
            for ((k, x, y) in multiples) {
                val d = k.padStart(2 * curve.size, '0').hexToByteArray()
                assertEquals("04$x$y", curve.publicKey(d).toHexString(), k)
            }
    }

    /** Blank-line separated `Key = value` blocks, `#` lines skipped (the ring/BoringSSL test file form). */
    fun blocks(resource: String): List<Map<String, String>> =
        javaClass.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }.decodeToString()
            .split("\n\n").map { block ->
                block.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore(" = ") to it.substringAfter(" = ") }
            }.filter { it.isNotEmpty() }

    @Test fun cavpSigVer() {
        var counted = 0
        for (case in blocks("ecdsa/ecdsa_verify_tests.txt")) {
            val curve = curve(case.getValue("Curve")) ?: continue
            val q = "04${case.getValue("X")}${case.getValue("Y")}".hexToByteArray()
            val valid = case["Invalid"] == null
            assertEquals(valid, curve.verify(q, case.getValue("Digest").hexToByteArray(), case.getValue("R").hexToByteArray(), case.getValue("S").hexToByteArray()), case.toString())
            counted++
        }
        assertEquals(170, counted)
    }

    @Test fun wycheproof() {
        var counted = 0
        for (case in blocks("ecdsa/wycheproof_ecdsa.txt")) {
            val curve = curve(case.getValue("Curve"))!!
            val q = "04${case.getValue("X")}${case.getValue("Y")}".hexToByteArray()
            val message = case.getValue("Msg").hexToByteArray()
            val digest = if (curve === WeierstrassCurve.P256) Sha256Pure.digest(message) else Sha384Pure.digest(message)
            val valid = case["Invalid"] == null
            assertEquals(valid, curve.verify(q, digest, case.getValue("Sig").hexToByteArray()), case.toString())
            counted++
        }
        assertEquals(386 + 407, counted)
    }

    fun spec(curve: WeierstrassCurve) = ECGenParameterSpec(if (curve === WeierstrassCurve.P256) "secp256r1" else "secp384r1")

    fun encode(curve: WeierstrassCurve, w: ECPoint) = byteArrayOf(4) + unsigned(w.affineX, curve.size) + unsigned(w.affineY, curve.size)

    fun unsigned(value: BigInteger, size: Int): ByteArray = value.toByteArray().let { it.copyOfRange(maxOf(0, it.size - size), it.size) }.let { ByteArray(size - it.size) + it }

    @Test fun jdkEcdh() {
        for (curve in listOf(WeierstrassCurve.P256, WeierstrassCurve.P384)) {
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(spec(curve)) }
            repeat(40) {
                val jdk = generator.generateKeyPair()
                val jdkPrivate = jdk.private as ECPrivateKey
                // The JDK's private scalar gives the JDK's public point.
                assertContentEquals(encode(curve, (jdk.public as ECPublicKey).w), curve.publicKey(unsigned(jdkPrivate.s, curve.size)))
                val d = curve.privateKey()
                val q = curve.publicKey(d)
                val params = (jdk.public as ECPublicKey).params
                val ours = KeyFactory.getInstance("EC").generatePublic(
                    ECPublicKeySpec(ECPoint(BigInteger(1, q.copyOfRange(1, 1 + curve.size)), BigInteger(1, q.copyOfRange(1 + curve.size, q.size))), params),
                )
                val shared = KeyAgreement.getInstance("ECDH").run { init(jdk.private); doPhase(ours, true); generateSecret() }
                assertContentEquals(shared, curve.ecdh(d, encode(curve, (jdk.public as ECPublicKey).w)))
            }
        }
    }

    @Test fun jdkEcdsa() {
        val random = Random(1865)
        val hashes = listOf("SHA256withECDSA" to { m: ByteArray -> Sha256Pure.digest(m) }, "SHA384withECDSA" to { m: ByteArray -> Sha384Pure.digest(m) })
        for (curve in listOf(WeierstrassCurve.P256, WeierstrassCurve.P384)) {
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(spec(curve)) }
            repeat(20) {
                val pair = generator.generateKeyPair()
                val q = encode(curve, (pair.public as ECPublicKey).w)
                for ((algorithm, hash) in hashes) {
                    val message = random.nextBytes(random.nextInt(0, 300))
                    val signature = Signature.getInstance(algorithm).run { initSign(pair.private); update(message); sign() }
                    assertTrue(curve.verify(q, hash(message), signature), algorithm)
                    assertFalse(curve.verify(q, hash(message + 0), signature), algorithm)
                    val flipped = signature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
                    assertFalse(curve.verify(q, hash(message), flipped), algorithm)
                }
            }
        }
    }

    @Test fun offCurvePointsRefused() {
        val curve = WeierstrassCurve.P256
        val q = curve.publicKey(curve.privateKey())
        val moved = q.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(curve.decode(moved))
        assertNull(curve.ecdh(curve.privateKey(), moved))
        assertNull(curve.decode(byteArrayOf(4) + ByteArray(64)))
        assertNull(curve.decode(q.copyOf(64)))
    }
}
