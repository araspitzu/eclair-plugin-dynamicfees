import fr.acinq.dynamicfees.app.FeeAdjusterActor.{DynamicFeeRow, DynamicFeesBreakdown}
import fr.acinq.eclair.ShortChannelId
import org.scalatest.FunSuite

class FeeAdjusterSpec extends FunSuite {

  test("configuration should allow only one between blacklist and whitelist") {

    // both empty list is okay
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0D, 0D), DynamicFeeRow(0D, 0D), List.empty, List.empty)

    // one of the two non empty is okay
    dfb.copy(whitelist = List(ShortChannelId("1x2x3")))
    dfb.copy(blacklist = List(ShortChannelId("1x2x3")))

    // both non empty list NOT okay
    assertThrows[IllegalArgumentException](dfb.copy(whitelist = List(ShortChannelId("0x0x0")), blacklist = List(ShortChannelId("1x2x3"))))
  }



}
