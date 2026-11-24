package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictionaryEntriesTest {
    /**
     * Black's Law Dictionary, 1st edition (1891), archive.org dictionaryof_blac_1891_00_20160211, OCR text as printed:
     * the BAILLI entry, then the BAILMENT entry through the end of the 2371 bytes at byte offset 597119. Not corrected.
     */
    private val passage = "BAILLI.  Ill  old  French  law.  One  to \nwhom  judicial  authority  was  assigned  or \ndelivered  by  a superior. \n\nBAILMENT.  A delivery  of  gooils  or \n]>ersonul  property,  by  one  person  to  another, \nin  trust  for  the  execution  of  a special  object \nupon  or  In  relation  to  sucli  goods,  benetlcial \neither  to  the  bailor  or  bailee  or  both,  and \nupon  a contract,  express  or  Implied,  to  per- \nform the  trust  and  carry  out  such  olijecl.  and \ntlu*reu|>on  either  to  re<loiiver  the  g<\u00BBods  to \nthe  bailor  or  otherwise  dis{H>se  of  the  same  in \nconformity  with  the  purpose  of  the  trust. \nS\u00AB*e  Code  Ga.  18S2,  \u00A7 2058. \n\nA delivory  of  goods  la  trust  upon  u contract,  ox- \npruHsed  or  Impliod,  that  the  trust  shall  bo  faith- \nfully executed  on  tho  part  of  the  bailee.  3 Bl. \nCoifim.  4&T\u00BB. \n\nBaihnoat,  from  tho  French  hnitfer,  to  deliver,  is \na delivery  of  goods  fur  some  purpose,  upon  a con- \ntract, express  or  Implied,  that,  after  the  purpose \nhas  been  fulfilled,  they  shall  bo  rodelivorod  to  the \nbailor,  or  otherwise  dealt  with,  according  to  his \ndirections,  or  (as  the  case  may  be)  kept  till  he  re- \ncloiins  them.  2 Steph.  Comm.  bO. \n\nA delivery  of  goods  in  trust  upon  a contract,  ex-  j \npressed  or  implied,  that  the  trust  shall  bo  duly \nexecuted,  and  tho  goods  restored  by  the  bailee  as  ' \nsoon  as  the  purposes  of  the  bailmeut  shall  be  an- \nsworetL  2 Kent,  Comm.  559. \n\nBailiucoi  Is  a delivery  of  a thing  in  trust  for  i \nsotno  special  object  or  purpose,  and  upon  a con- \ntract, express  or  impliod,  to  conform  to  the  object \nor  purpose  of  the  trust.  Btory,  Ballm.  9. \n\nA delivery  of  goods  lu  trust  on  a contract,  cither  | \nexpressed  or  implied,  that  the  trust  shall  be  duly  I \nexecuted,  and  the  g\u00ABv)ds  redelivered  as  soon  as  tho \ntime  or  use  for  which  they  were  balled  shall  have \nehtpsed  or  bo  performed.  J onus.  Bull  m.  117. \n\n\nBailment  is  a word  of  French  origin,  significant \nof  the  curtailed  transfer,  the  delivery  or  mere \nbanding  over,  which  is  appropriate  to  tho  transao- \ntion.  Hchoulcr.  Pers.  Prop.  695. \n\nThe  test  of  a bailment  is  that  the  identical  thing \nis  to  be  returned ; if  another  thing  of  equal  value \nis  to  be  returned  the  iraosscUon  Is  a sole.  6 \nThomp.  & C.  29;  3 Hun,  550. \n\n"

    @Test fun eachEntryHasItsHeadwordAndItsDefinitionAndNoNeighbour() {
        val entries = DictionaryEntries.read(passage)
        assertEquals(listOf("BAILLI", "BAILMENT"), entries.map { it.headword })
        val bailment = entries[1].definition
        assertTrue(bailment.startsWith("A delivery"))
        assertTrue(bailment.endsWith("3 Hun, 550."))
        assertFalse(bailment.contains("judicial"))
        assertFalse(bailment.contains("old French law"))
    }

    @Test fun textWithoutAHeadwordHasNoEntries() {
        assertEquals(emptyList(), DictionaryEntries.read("a delivery of goods in trust.\n\nmore text"))
    }
}
