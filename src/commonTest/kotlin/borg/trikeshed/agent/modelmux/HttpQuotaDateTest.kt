package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import kotlin.test.*

class HttpQuotaDateTest {
    @Test fun httpDateRequiresAValidUtcDateAndWeekday() {
        assertEquals(1_445_412_480_000L, HttpQuota.reset("Wed, 21 Oct 2015 07:28:00 GMT", 0L))
        for (bad in _l[
            "Thu, 21 Oct 2015 07:28:00 GMT",
            "Wed, 31 Feb 2015 07:28:00 GMT",
            "Wed, 21 Oct 2015 07:28:00 UTC",
            "Wed, 21 Oct 2015 07:28:00 GMTx",
        ]) assertNull(HttpQuota.reset(bad, 0L), bad)
    }
}
