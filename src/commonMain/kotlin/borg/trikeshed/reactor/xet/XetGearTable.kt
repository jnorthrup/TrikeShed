package borg.trikeshed.reactor.xet

/**
 * Gearhash table used by Xet content-defined chunking: `rust-gearhash` DEFAULT_TABLE
 * (github.com/srijs/rust-gearhash, src/table.rs), 256 x u64. The Xet chunking spec names this table
 * ("TABLE[256] ... rust-gearhash-table") without listing it.
 */
internal object XetGearTable {
    val TABLE: LongArray = longArrayOf(
        0xb088d3a9e840f559UL.toLong(), 0x5652c7f739ed20d6UL.toLong(), 0x45b28969898972abUL.toLong(), 0x6b0a89d5b68ec777UL.toLong(),
        0x368f573e8b7a31b7UL.toLong(), 0x1dc636dce936d94bUL.toLong(), 0x207a4c4e5554d5b6UL.toLong(), 0xa474b34628239acbUL.toLong(),
        0x3b06a83e1ca3b912UL.toLong(), 0x90e78d6c2f02baf7UL.toLong(), 0xe1c92df7150d9a8aUL.toLong(), 0x8e95053a1086d3adUL.toLong(),
        0x5a2ef4f1b83a0722UL.toLong(), 0xa50fac949f807faeUL.toLong(), 0x0e7303eb80d8d681UL.toLong(), 0x99b07edc1570ad0fUL.toLong(),
        0x689d2fb555fd3076UL.toLong(), 0x00005082119ea468UL.toLong(), 0xc4b08306a88fcc28UL.toLong(), 0x3eb0678af6374afdUL.toLong(),
        0xf19f87ab86ad7436UL.toLong(), 0xf2129fbfbe6bc736UL.toLong(), 0x481149575c98a4edUL.toLong(), 0x0000010695477bc5UL.toLong(),
        0x1fba37801a9ceaccUL.toLong(), 0x3bf06fd663a49b6dUL.toLong(), 0x99687e9782e3874bUL.toLong(), 0x79a10673aa50d8e3UL.toLong(),
        0xe4accf9e6211f420UL.toLong(), 0x2520e71f87579071UL.toLong(), 0x2bd5d3fd781a8a9bUL.toLong(), 0x00de4dcddd11c873UL.toLong(),
        0xeaa9311c5a87392fUL.toLong(), 0xdb748eb617bc40ffUL.toLong(), 0xaf579a8df620bf6fUL.toLong(), 0x86a6e5da1b09c2b1UL.toLong(),
        0xcc2fc30ac322a12eUL.toLong(), 0x355e2afec1f74267UL.toLong(), 0x2d99c8f4c021a47bUL.toLong(), 0xbade4b4a9404cfc3UL.toLong(),
        0xf7b518721d707d69UL.toLong(), 0x3286b6587bf32c20UL.toLong(), 0x0000b68886af270cUL.toLong(), 0xa115d6e4db8a9079UL.toLong(),
        0x484f7e9c97b2e199UL.toLong(), 0xccca7bb75713e301UL.toLong(), 0xbf2584a62bb0f160UL.toLong(), 0xade7e813625dbcc8UL.toLong(),
        0x000070940d87955aUL.toLong(), 0x8ae69108139e626fUL.toLong(), 0xbd776ad72fde38a2UL.toLong(), 0xfb6b001fc2fcc0cfUL.toLong(),
        0xc7a474b8e67bc427UL.toLong(), 0xbaf6f11610eb5d58UL.toLong(), 0x09cb1f5b6de770d1UL.toLong(), 0xb0b219e6977d4c47UL.toLong(),
        0x00ccbc386ea7ad4aUL.toLong(), 0xcc849d0adf973f01UL.toLong(), 0x73a3ef7d016af770UL.toLong(), 0xc807d2d386bdbdfeUL.toLong(),
        0x7f2ac9966c791730UL.toLong(), 0xd037a86bc6c504daUL.toLong(), 0xf3f17c661eaa609dUL.toLong(), 0xaca626b04daae687UL.toLong(),
        0x755a99374f4a5b07UL.toLong(), 0x90837ee65b2caedeUL.toLong(), 0x6ee8ad93fd560785UL.toLong(), 0x0000d9e11053edd8UL.toLong(),
        0x9e063bb2d21cdbd7UL.toLong(), 0x07ab77f12a01d2b2UL.toLong(), 0xec550255e6641b44UL.toLong(), 0x78fb94a8449c14c6UL.toLong(),
        0xc7510e1bc6c0f5f5UL.toLong(), 0x0000320b36e4cae3UL.toLong(), 0x827c33262c8b1a2dUL.toLong(), 0x14675f0b48ea4144UL.toLong(),
        0x267bd3a6498decebUL.toLong(), 0xf1916ff982f5035eUL.toLong(), 0x86221b7ff434fb88UL.toLong(), 0x9dbecee7386f49d8UL.toLong(),
        0xea58f8cac80f8f4aUL.toLong(), 0x008d198692fc64d8UL.toLong(), 0x6d38704fbabf9a36UL.toLong(), 0xe032cb07d1e7be4cUL.toLong(),
        0x228d21f6ad450890UL.toLong(), 0x635cb1bfc02589a5UL.toLong(), 0x4620a1739ca2ce71UL.toLong(), 0xa7e7dfe3aae5fb58UL.toLong(),
        0x0c10ca932b3c0debUL.toLong(), 0x2727fee884afed7bUL.toLong(), 0xa2df1c6df9e2ab1fUL.toLong(), 0x4dcdd1ac0774f523UL.toLong(),
        0x000070ffad33e24eUL.toLong(), 0xa2ace87bc5977816UL.toLong(), 0x9892275ab4286049UL.toLong(), 0xc2861181ddf18959UL.toLong(),
        0xbb9972a042483e19UL.toLong(), 0xef70cd3766513078UL.toLong(), 0x00000513abfc9864UL.toLong(), 0xc058b61858c94083UL.toLong(),
        0x09e850859725e0deUL.toLong(), 0x9197fb3bf83e7d94UL.toLong(), 0x7e1e626d12b64bceUL.toLong(), 0x520c54507f7b57d1UL.toLong(),
        0xbee1797174e22416UL.toLong(), 0x6fd9ac3222e95587UL.toLong(), 0x0023957c9adfbf3eUL.toLong(), 0xa01c7d7e234bbe15UL.toLong(),
        0xaba2c758b8a38cbbUL.toLong(), 0x0d1fa0ceec3e2b30UL.toLong(), 0x0bb6a58b7e60b991UL.toLong(), 0x4333dd5b9fa26635UL.toLong(),
        0xc2fd3b7d4001c1a3UL.toLong(), 0xfb41802454731127UL.toLong(), 0x65a56185a50d18cbUL.toLong(), 0xf67a02bd8784b54fUL.toLong(),
        0x696f11dd67e65063UL.toLong(), 0x00002022fca814abUL.toLong(), 0x8cd6be912db9d852UL.toLong(), 0x695189b6e9ae8a57UL.toLong(),
        0xee9453b50ada0c28UL.toLong(), 0xd8fc5ea91a78845eUL.toLong(), 0xab86bf191a4aa767UL.toLong(), 0x0000c6b5c86415e5UL.toLong(),
        0x267310178e08a22eUL.toLong(), 0xed2d101b078bca25UL.toLong(), 0x3b41ed84b226a8fbUL.toLong(), 0x13e622120f28dc06UL.toLong(),
        0xa315f5ebfb706d26UL.toLong(), 0x8816c34e3301baceUL.toLong(), 0xe9395b9cbb71fdaeUL.toLong(), 0x002ce9202e721648UL.toLong(),
        0x4283db1d2bb3c91cUL.toLong(), 0xd77d461ad2b1a6a5UL.toLong(), 0xe2ec17e46eeb866bUL.toLong(), 0xb8e0be4039fbc47cUL.toLong(),
        0xdea160c4d5299d04UL.toLong(), 0x7eec86c8d28c3634UL.toLong(), 0x2119ad129f98a399UL.toLong(), 0xa6ccf46b61a283efUL.toLong(),
        0x2c52cedef658c617UL.toLong(), 0x2db4871169acdd83UL.toLong(), 0x0000f0d6f39ecbe9UL.toLong(), 0x3dd5d8c98d2f9489UL.toLong(),
        0x8a1872a22b01f584UL.toLong(), 0xf282a4c40e7b3cf2UL.toLong(), 0x8020ec2ccb1ba196UL.toLong(), 0x6693b6e09e59e313UL.toLong(),
        0x0000ce19cc7c83ebUL.toLong(), 0x20cb5735f6479c3bUL.toLong(), 0x762ebf3759d75a5bUL.toLong(), 0x207bfe823d693975UL.toLong(),
        0xd77dc112339cd9d5UL.toLong(), 0x9ba7834284627d03UL.toLong(), 0x217dc513e95f51e9UL.toLong(), 0xb27b1a29fc5e7816UL.toLong(),
        0x00d5cd9831bb662dUL.toLong(), 0x71e39b806d75734cUL.toLong(), 0x7e572af006fb1a23UL.toLong(), 0xa2734f2f6ae91f85UL.toLong(),
        0xbf82c6b5022cddf2UL.toLong(), 0x5c3beac60761a0deUL.toLong(), 0xcdc893bb47416998UL.toLong(), 0x6d1085615c187e01UL.toLong(),
        0x77f8ae30ac277c5dUL.toLong(), 0x917c6b81122a2c91UL.toLong(), 0x5b75b699add16967UL.toLong(), 0x0000cf6ae79a069bUL.toLong(),
        0xf3c40afa60de1104UL.toLong(), 0x2063127aa59167c3UL.toLong(), 0x621de62269d1894dUL.toLong(), 0xd188ac1de62b4726UL.toLong(),
        0x107036e2154b673cUL.toLong(), 0x0000b85f28553a1dUL.toLong(), 0xf2ef4e4c18236f3dUL.toLong(), 0xd9d6de6611b9f602UL.toLong(),
        0xa1fc7955fb47911cUL.toLong(), 0xeb85fd032f298dbdUL.toLong(), 0xbe27502fb3befae1UL.toLong(), 0xe3034251c4cd661eUL.toLong(),
        0x441364d354071836UL.toLong(), 0x0082b36c75f2983eUL.toLong(), 0xb145910316fa66f0UL.toLong(), 0x021c069c9847caf7UL.toLong(),
        0x2910dfc75a4b5221UL.toLong(), 0x735b353e1c57a8b5UL.toLong(), 0xce44312ce98ed96cUL.toLong(), 0xbc942e4506bdfa65UL.toLong(),
        0xf05086a71257941bUL.toLong(), 0xfec3b215d351ceadUL.toLong(), 0x00ae1055e0144202UL.toLong(), 0xf54b40846f42e454UL.toLong(),
        0x00007fd9c8bcbcc8UL.toLong(), 0xbfbd9ef317de9bfeUL.toLong(), 0xa804302ff2854e12UL.toLong(), 0x39ce4957a5e5d8d4UL.toLong(),
        0xffb9e2a45637ba84UL.toLong(), 0x55b9ad1d9ea0818bUL.toLong(), 0x00008acbf319178aUL.toLong(), 0x48e2bfc8d0fbfb38UL.toLong(),
        0x8be39841e848b5e8UL.toLong(), 0x0e2712160696a08bUL.toLong(), 0xd51096e84b44242aUL.toLong(), 0x1101ba176792e13aUL.toLong(),
        0xc22e770f4531689dUL.toLong(), 0x1689eff272bbc56cUL.toLong(), 0x00a92a197f5650ecUL.toLong(), 0xbc765990bda1784eUL.toLong(),
        0xc61441e392fcb8aeUL.toLong(), 0x07e13a2ced31e4a0UL.toLong(), 0x92cbe984234e9d4dUL.toLong(), 0x8f4ff572bb7d8ac5UL.toLong(),
        0x0b9670c00b963bd0UL.toLong(), 0x62955a581a03eb01UL.toLong(), 0x645f83e5ea000254UL.toLong(), 0x41fce516cd88f299UL.toLong(),
        0xbbda9748da7a98cfUL.toLong(), 0x0000aab2fe4845faUL.toLong(), 0x19761b069bf56555UL.toLong(), 0x8b8f5e8343b6ad56UL.toLong(),
        0x3e5d1cfd144821d9UL.toLong(), 0xec5c1e2ca2b0cd8fUL.toLong(), 0xfaf7e0fea7fbb57fUL.toLong(), 0x000000d3ba12961bUL.toLong(),
        0xda3f90178401b18eUL.toLong(), 0x70ff906de33a5febUL.toLong(), 0x0527d5a7c06970e7UL.toLong(), 0x22d8e773607c13e9UL.toLong(),
        0xc9ab70df643c3bacUL.toLong(), 0xeda4c6dc8abe12e3UL.toLong(), 0xecef1f410033e78aUL.toLong(), 0x0024c2b274ac72cbUL.toLong(),
        0x06740d954fa900b4UL.toLong(), 0x1d7a299b323d6304UL.toLong(), 0xb3c37cb298cbead5UL.toLong(), 0xc986e3c76178739bUL.toLong(),
        0x9fabea364b46f58aUL.toLong(), 0x6da214c5af85cc56UL.toLong(), 0x17a43ed8b7a38f84UL.toLong(), 0x6eccec511d9adbebUL.toLong(),
        0xf9cab30913335afbUL.toLong(), 0x4a5e60c5f415eed2UL.toLong(), 0x00006967503672b4UL.toLong(), 0x9da51d121454bb87UL.toLong(),
        0x84321e13b9bbc816UL.toLong(), 0xfb3d6fb6ab2fdd8dUL.toLong(), 0x60305eed8e160a8dUL.toLong(), 0xcbbf4b14e9946ce8UL.toLong(),
        0x00004f63381b10c3UL.toLong(), 0x07d5b7816fcc4e10UL.toLong(), 0xe5a536726a6a8155UL.toLong(), 0x57afb23447a07fddUL.toLong(),
        0x18f346f7abc9d394UL.toLong(), 0x636dc655d61ad33dUL.toLong(), 0xcc8bab4939f7f3f6UL.toLong(), 0x63c7a906c1dd187bUL.toLong(),
    )

    init {
        check(TABLE.size == 256)
    }
}
